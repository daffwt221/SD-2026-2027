"""Integration tests: real Java processes and Kubo; Python standard library only.

Run: python testes/verificar.py --ipfs <path/to/ipfs.exe>
Uses temporary Kubo repositories, loopback-only APIs and a separate multicast group.
Never modifies the student's lider.txt, replica-*.txt or default IPFS repository.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JAVA = str(Path(shutil.which("java")).resolve())
JAVAC = str(Path(shutil.which("javac")).resolve())
GROUP = "239.255.71.99"
PORT = 16799
TOPIC = "rep01-temperaturas"


def mb(value):
    return "u" + base64.urlsafe_b64encode(value.encode()).decode().rstrip("=")


def wait_until(condition, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(0.05)
    raise AssertionError("Timed out waiting for integration-test condition")


def rpc(port, command, query=None, data=b"", headers=None):
    suffix = "?" + urllib.parse.urlencode(query or {}) if query else ""
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/api/v0/{command}{suffix}", data=data,
        headers=headers or {}, method="POST")
    with urllib.request.urlopen(request, timeout=5) as response:
        body = response.read()
    return json.loads(body) if body else {}


def publish(port, text, topic=TOPIC):
    boundary = "rep01-python-test-boundary"
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"msg\""
            f"\r\nContent-Type: application/octet-stream\r\n\r\n{text}\r\n--{boundary}--\r\n").encode()
    return rpc(port, "pubsub/pub", {"arg": mb(topic)}, body,
               {"Content-Type": "multipart/form-data; boundary=" + boundary})


def udp(text):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP) as sender:
        sender.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
        sender.sendto(text.encode(), (GROUP, PORT))


def text(path):
    return path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""


def sequences(path):
    return [int(line.split(";")[0]) for line in text(path).splitlines() if line.strip()]


class Process:
    def __init__(self, command, cwd, log, env=None):
        self.log = log
        self.handle = log.open("a", encoding="utf-8")
        self.process = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.PIPE,
                                        stdout=self.handle, stderr=self.handle, text=True,
                                        encoding="utf-8")

    def send(self, lines):
        self.process.stdin.write(lines + "\n")
        self.process.stdin.flush()

    def finish(self):
        self.process.wait(timeout=90)
        assert self.process.returncode == 0, text(self.log)

    def stop(self):
        if self.process.poll() is None:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(self.process.pid), "/T", "/F"], capture_output=True)
            else:
                self.process.kill()
            self.process.wait(timeout=10)
        self.handle.close()


class Suite:
    def __init__(self, ipfs):
        self.ipfs = str(Path(ipfs).resolve())
        self.work = Path(tempfile.mkdtemp(prefix="comindir-completo-"))
        self.out = ROOT / "resultados" / time.strftime("%Y%m%d-%H%M%S")
        self.out.mkdir(parents=True)
        self.processes = []
        self.nodes = {}
        self.result = {"java": subprocess.check_output([JAVA, "-version"], stderr=subprocess.STDOUT, text=True),
                       "kubo": subprocess.check_output([self.ipfs, "version"], text=True).strip(),
                       "multicast_group": GROUP, "multicast_port": PORT,
                       "measurements": [], "faults": [], "checks": []}
        for role in ("lider", "replica"):
            destination = self.work / role
            destination.mkdir()
            subprocess.run([JAVAC, "--release", "11", "-encoding", "UTF-8", "-d", str(destination),
                            *map(str, (ROOT / f"rep01-{role}" / "src" / "rep01").glob("*.java"))],
                           check=True, capture_output=True)

    def spawn(self, command, cwd, log, env=None):
        process = Process(command, cwd, log, env)
        self.processes.append(process)
        return process

    def node(self, number, enabled=True):
        repo = self.work / f"ipfs-{number}"
        env = os.environ.copy()
        env["IPFS_PATH"] = str(repo)
        subprocess.run([self.ipfs, "init", "--profile=test"], env=env, check=True,
                       capture_output=True, timeout=60)
        config_path = repo / "config"
        config = json.loads(config_path.read_text())
        config.setdefault("Pubsub", {})["Enabled"] = enabled
        config["Addresses"]["API"] = f"/ip4/127.0.0.1/tcp/{15500 + number}"
        config["Addresses"]["Gateway"] = f"/ip4/127.0.0.1/tcp/{18500 + number}"
        config["Addresses"]["Swarm"] = [f"/ip4/127.0.0.1/tcp/{14500 + number}"]
        config["Bootstrap"] = []
        config.setdefault("Discovery", {}).setdefault("MDNS", {})["Enabled"] = False
        config.setdefault("Routing", {})["Type"] = "none"
        config.setdefault("AutoConf", {})["Enabled"] = False
        config_path.write_text(json.dumps(config), encoding="utf-8")
        process = self.spawn([self.ipfs, "daemon"], self.work, self.out / f"kubo-{number}.log", env)
        self.nodes[number] = {"process": process, "env": env, "port": 15500 + number,
                              "id": config["Identity"]["PeerID"], "enabled": enabled}
        def ready():
            if process.process.poll() is not None:
                raise AssertionError(text(process.log))
            try:
                return bool(rpc(15500 + number, "version"))
            except OSError:
                return False
        wait_until(ready, 60)

    def scenario(self, name):
        path = self.out / name
        path.mkdir()
        return path

    def java(self, role, klass, args, path, label, props=None):
        props = {"rep01.group": GROUP, "rep01.port": PORT, **(props or {})}
        command = [JAVA, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                   "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8", "-Djava.net.preferIPv4Stack=true"]
        command += [f"-D{key}={value}" for key, value in props.items()]
        command += ["-cp", str(self.work / role), "rep01." + klass, *map(str, args)]
        return self.spawn(command, path, path / (label + ".log"))

    def leader(self, transport, path, lines, node=1, mode="automatico", props=None, wait=True):
        args = [mode] if transport == "multicast" else [f"http://127.0.0.1:{15500 + node}/api/v0", mode]
        process = self.java("lider", "MulticastLeader" if transport == "multicast" else "PubSubLeader",
                            args, path, "lider", props)
        process.send(lines + "\nsair")
        if wait:
            process.finish()
        return process

    def replica(self, transport, path, number, node=1, props=None, size=1000, first=1):
        args = [number, size, first] if transport == "multicast" else [number, 15500 + node, size, first]
        klass = "MulticastReplica" if transport == "multicast" else "PubSubReplica"
        process = self.java("replica", klass, args, path, "replica-" + str(number), props)
        wait_until(lambda: "escuta" in text(process.log) or "a subscrever" in text(process.log)
                   or "ERRO:" in text(process.log))
        if "ERRO:" in text(process.log):
            return process
        if transport != "multicast":
            if not self.nodes[node]["enabled"]:
                wait_until(lambda: "ERRO:" in text(process.log))
                return process
            wait_until(lambda: bool(rpc(15500 + node, "pubsub/ls").get("Strings")))
            publish(15500 + node, "aquecimento", (props or {}).get("rep01.topic", TOPIC))
            wait_until(lambda: "ativa:" in text(process.log))
        return process

    def connect(self):
        address = f"/ip4/127.0.0.1/tcp/14502/p2p/{self.nodes[2]['id']}"
        self.result["swarm_connect"] = rpc(15501, "swarm/connect", {"arg": address})

    def smoke_cli(self):
        path = self.scenario("cli")
        subscriber = self.spawn([self.ipfs, "pubsub", "sub", TOPIC], path, path / "sub.log", self.nodes[1]["env"])
        wait_until(lambda: bool(rpc(15501, "pubsub/ls").get("Strings")))
        result = subprocess.run([self.ipfs, "pubsub", "pub", TOPIC], env=self.nodes[1]["env"],
                                input="teste-cli\n", text=True, capture_output=True, timeout=15)
        if result.returncode != 0:
            raise AssertionError(result.stderr)
        wait_until(lambda: "teste-cli" in text(subscriber.log))
        subscriber.stop()
        self.result["checks"].append("Kubo CLI sub/pub: PASS")

    def measure(self, transport, two_nodes=False):
        name = "multicast" if transport == "multicast" else ("pubsub-dois-nos" if two_nodes else "pubsub-um-no")
        path = self.scenario(name)
        replicas = [self.replica(transport, path, i, 2 if two_nodes and i > 1 else 1) for i in (1, 2, 3)]
        if two_nodes:
            self.connect()
            wait_until(lambda: self.nodes[2]["id"] in rpc(15501, "pubsub/peers", {"arg": mb(TOPIC)}).get("Strings", []), 30)
            time.sleep(2)
            self.result["pubsub_peers"] = rpc(15501, "pubsub/peers", {"arg": mb(TOPIC)})
        for run in (1, 2, 3):
            self.leader(transport, path, "rajada 1000")
            first, last = (run - 1) * 1000 + 1, run * 1000
            marker = f"RAJADA (parcial) seq={first}..{last} "
            wait_until(lambda: all(marker in text(p.log) for p in replicas), 30)
            leader_line = [line for line in text(path / "lider.log").splitlines() if f"RAJADA seq={first}..{last} " in line][-1]
            lv = dict(re.findall(r"(\w+)=([^\s]+)", leader_line))
            for i, process in enumerate(replicas, 1):
                line = [line for line in text(process.log).splitlines() if marker in line][-1]
                rv = dict(re.findall(r"(\w+)=([^\s]+)", line))
                saved = sequences(path / f"replica-{i}.txt")
                assert saved == list(range(1, len(saved) + 1)), (name, i, "file has gaps")
                assert sum(first <= seq <= last for seq in saved) == int(rv["escritos"])
                assert int(rv["unicos"]) == int(rv["escritos"]) + int(rv["retidos"])
                self.result["measurements"].append({"scenario": name, "run": run, "replica": i,
                    "leader": lv, "replica_metrics": rv,
                    "latency_ms": int(rv["ultimaRececao"]) - int(lv["primeiroEnvio"])})
            print(f"PASS {name}: rajada {run} / 1000, tres replicas", flush=True)
        assert sequences(path / "lider.txt") == list(range(1, 3001))
        for process in replicas:
            process.stop()
        self.save()

    def fault(self, name, mechanism, path, side, observation):
        self.result["faults"].append({"name": name, "mechanism": mechanism,
                                     "scenario": path.name, "side": side, "observation": observation})
        print("PASS fault: " + name, flush=True)
        self.save()

    def ordering(self, transport):
        path = self.scenario(transport + "-ordem")
        replica = self.replica(transport, path, 1)
        def send(seq):
            message = f"{seq};S01;20;2026-10-15T12:00:00"
            udp(message) if transport == "multicast" else publish(15501, message)
            time.sleep(0.1)
        send(2); send(2); send(3)
        assert not sequences(path / "replica-1.txt")
        send(1)
        wait_until(lambda: sequences(path / "replica-1.txt") == [1, 2, 3])
        send(1)
        udp("ola") if transport == "multicast" else publish(15501, "ola")
        wait_until(lambda: "REJEITADO: ola" in text(replica.log))
        assert "DUPLICADO: 2" in text(replica.log)
        assert "DUPLICADO: 1" in text(replica.log)
        replica.stop()
        self.result["checks"].append(transport + " ordering/duplicates/invalid: PASS")

    def late(self, transport):
        path = self.scenario(transport + "-arranque-tardio")
        self.leader(transport, path, "S01 20\nS02 21\nS03 22")
        replica = self.replica(transport, path, 1)
        self.leader(transport, path, "S04 23\nS05 24")
        wait_until(lambda: "EM ESPERA: 5" in text(replica.log))
        assert sequences(path / "lider.txt") == [1, 2, 3, 4, 5]
        assert not sequences(path / "replica-1.txt")
        replica.stop()
        self.fault("leader starts before replicas", transport, path, "replica",
                   "No exception; file empty, seq 4 and 5 retained, missing seq 1.")

    def interruption(self, transport):
        path = self.scenario(transport + "-interrupcao")
        replicas = {i: self.replica(transport, path, i) for i in (1, 2, 3)}
        self.leader(transport, path, "S01 20\nS02 21\nS03 22\nS04 23\nS05 24")
        wait_until(lambda: all(len(sequences(path / f"replica-{i}.txt")) == 5 for i in replicas))
        replicas[2].stop()
        self.leader(transport, path, "S06 25\nS07 26\nS08 27")
        replicas[2] = self.replica(transport, path, 2)
        self.leader(transport, path, "S09 28\nS10 29")
        wait_until(lambda: "EM ESPERA: 10" in text(replicas[2].log))
        assert sequences(path / "replica-2.txt") == [1, 2, 3, 4, 5]
        wait_until(lambda: all(sequences(path / f"replica-{i}.txt") == list(range(1, 11)) for i in (1, 3)))
        for process in replicas.values(): process.stop()
        self.result["checks"].append(transport + " interruption 5 + offline 3 + online 2: PASS")

    def multicast_faults(self):
        path = self.scenario("multicast-grupo-errado")
        replica = self.replica("multicast", path, 1, props={"rep01.group": "239.255.71.100"})
        self.leader("multicast", path, "S01 20")
        time.sleep(1.2)
        assert not sequences(path / "replica-1.txt")
        replica.stop()
        self.fault("wrong multicast group", "multicast", path, "replica", "No exception; no records received; files diverge.")
        path = self.scenario("multicast-buffer10")
        replica = self.replica("multicast", path, 1, props={"rep01.buffer": 10})
        self.leader("multicast", path, "S01 20")
        wait_until(lambda: "REJEITADO:" in text(replica.log))
        assert not sequences(path / "replica-1.txt")
        replica.stop()
        self.fault("receive buffer 10 bytes", "multicast", path, "replica",
                   "Datagram truncated; IllegalArgumentException handled as REJEITADO, replica stays alive; files diverge.")
        path = self.scenario("multicast-paragem-na-rajada")
        replica = self.replica("multicast", path, 1)
        leader = self.leader("multicast", path, "rajada 1000", wait=False)
        wait_until(lambda: len(sequences(path / "replica-1.txt")) >= 5)
        replica.stop()
        leader.finish()
        saved_before = sequences(path / "replica-1.txt")
        assert len(saved_before) < 1000, "Replica was not stopped during burst"
        replica = self.replica("multicast", path, 1)
        self.leader("multicast", path, "S01 20")
        wait_until(lambda: "EM ESPERA: 1001" in text(replica.log))
        assert sequences(path / "replica-1.txt") == saved_before
        replica.stop()
        self.fault("stop replica during burst", "multicast", path, "replica",
                   f"No exception; saved {len(saved_before)} / 1000 before stop; seq 1001 retained after restart.")

    def pubsub_faults(self):
        path = self.scenario("pubsub-topico-errado")
        replica = self.replica("pubsub", path, 1, props={"rep01.topic": "rep01-temp"})
        self.leader("pubsub", path, "S01 20")
        time.sleep(1.2)
        assert not sequences(path / "replica-1.txt")
        replica.stop()
        self.fault("wrong topic", "pubsub", path, "replica", "No exception; no records received; files diverge.")
        path = self.scenario("pubsub-desativado")
        replica = self.replica("pubsub", path, 1, node=3)
        wait_until(lambda: "ERRO:" in text(replica.log))
        self.leader("pubsub", path, "S01 20")
        assert sequences(path / "lider.txt") == [1]
        assert not sequences(path / "replica-1.txt")
        replica.stop()
        self.fault("Pubsub.Enabled false", "pubsub", path, "replica",
                   text(replica.log).strip() + " | Leader file has seq 1; replica file empty: files diverge.")
        # Remove the actual connection; bootstrap and mDNS were disabled before daemon startup.
        rpc(15501, "swarm/disconnect", {"arg": f"/ip4/127.0.0.1/tcp/14502/p2p/{self.nodes[2]['id']}"})
        wait_until(lambda: not rpc(15501, "swarm/peers").get("Peers"))
        path = self.scenario("pubsub-nos-desligados")
        replica = self.replica("pubsub", path, 1, node=2)
        self.leader("pubsub", path, "S01 20")
        time.sleep(2)
        assert not sequences(path / "replica-1.txt")
        replica.stop()
        self.fault("two nodes disconnected", "pubsub", path, "replica",
                   "No exception; bootstrap empty, mDNS false, swarm peers empty; files diverge.")
        self.nodes[1]["process"].stop()
        path = self.scenario("pubsub-daemon-parado")
        self.leader("pubsub", path, "S01 20")
        assert "ConnectException" in text(path / "lider.log"), text(path / "lider.log")
        assert sequences(path / "lider.txt") == [1]
        self.fault("leader daemon stopped", "pubsub", path, "leader", text(path / "lider.log").strip())

    def save(self):
        (self.out / "resultados.json").write_text(json.dumps(self.result, indent=2, ensure_ascii=False), encoding="utf-8")

    def close(self):
        for process in reversed(self.processes):
            process.stop()
        self.save()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ipfs", required=True)
    args = parser.parse_args()
    suite = Suite(args.ipfs)
    print("Results: " + str(suite.out), flush=True)
    try:
        for number in (1, 2, 3):
            suite.node(number, enabled=number != 3)
            print("Kubo node " + str(number) + " ready", flush=True)
        suite.smoke_cli()
        suite.measure("multicast")
        suite.measure("pubsub")
        suite.measure("pubsub", two_nodes=True)
        for transport in ("multicast", "pubsub"):
            suite.ordering(transport)
            suite.late(transport)
            suite.interruption(transport)
        suite.multicast_faults()
        suite.pubsub_faults()
        suite.result["status"] = "PASS"
        print("PASS: all scenarios", flush=True)
    except Exception as error:
        suite.result["status"] = "FAIL"
        suite.result["error"] = repr(error)
        raise
    finally:
        suite.close()


if __name__ == "__main__":
    main()
