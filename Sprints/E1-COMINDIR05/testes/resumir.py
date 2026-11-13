"""Extrai medidas dos ficheiros reais; nunca escreve nem modifica as entradas."""
import base64
import csv
import json
import statistics
import sys
from pathlib import Path

def props(path):
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines()
                if line and not line.startswith("#") and "=" in line) if path.exists() else {}

def lines(path):
    return path.read_text(encoding="utf-8", errors="replace").splitlines() if path.exists() else []

def events(folder):
    for path in sorted(folder.glob("eventos-p*-n*.txt")):
        p = int(path.stem.split("-")[1][1:])
        node = int(path.stem.split("-")[2][1:])
        for line in lines(path):
            f = line.split(";", 3)
            if len(f) != 4:
                continue
            fields = dict(item.split("=", 1) for item in f[3].split(";") if "=" in item)
            yield dict(time=int(f[0]), event=f[1], p=p, node=node, detail=f[3], fields=fields)

def run(folder):
    summary = props(folder / "raftcheck-resumo.properties")
    causal = props(folder / "causalcheck-resumo.properties")
    meta = props(folder / "ensaio.properties")
    if not summary:
        return None
    result = dict(ensaio=folder.name, raft=int(meta["raftCheck"]), causal=int(meta["causalCheck"]))
    for key in ("leiturasEnviadas", "leiturasPerdidas", "registosTruncados", "truncadosComOutraSeq",
                "termosComMultiplosLideres", "termosDiminuiram", "votosDuplos", "seqDivergentes",
                "lotesObsoletosAplicados", "estadosAusentesAtrasados", "errosDosNos", "violacoes"):
        result[key] = int(summary[key])
    result["arranqueMs"] = [int(summary[f"primeiroLider.p{p}.ms"]) for p in range(3)]
    result["termosPrimeiroLider"] = [int(summary[f"termosAtePrimeiroLider.p{p}"]) for p in range(3)]
    result["votosDivididos"] = [int(summary[f"votosDivididos.p{p}"]) for p in range(3)]
    result["errosCausais"] = int(causal["errosLocais"]) + int(causal["errosLamport"])
    result["falhas"] = []
    inputs = []
    for line in lines(folder / "produtor.txt"):
        f = line.split(";")
        inputs.append((int(f[0]), int(f[1][1:]), f[2], f[3]))
    present = set()
    for path in folder.glob("no-p*-n*.txt"):
        p = int(path.stem.split("-")[1][1:])
        for line in lines(path):
            f = line.split(";")
            present.add((p, f[2], f[3]))
    lost = [item for item in inputs if item[1:] not in present]
    removed = set()
    ev = list(events(folder))
    for e in ev:
        if e["event"] == "TRUNCADO" and "registos64" in e["fields"]:
            raw = base64.b64decode(e["fields"]["registos64"]).decode("utf-8")
            for line in raw.splitlines():
                f = line.split(";")
                removed.add((e["p"], f[2], f[3]))
    result["perdidasPorTruncagem"] = sum(item[1:] in removed for item in lost)
    intervals = []
    for index, line in enumerate(lines(folder / "falhas.txt")):
        f = line.split(";")
        leader = summary.get(f"falha.{index}.eraLider") == "true"
        duration = int(summary[f"falha.{index}.ms"])
        time = int(f[2])
        if leader and duration >= 0:
            intervals.append((int(f[0][1:]), time, time + duration))
        result["falhas"].append(dict(no=f[1], eraLider=leader, ms=duration,
                                    leiturasDurante=int(summary[f"falha.{index}.leituras"])))
    result["perdidasDuranteEleicao"] = sum(any(p == item[1] and a <= item[0] < b
                                                for p, a, b in intervals) for item in lost)
    result["perdidasForaDestasCausas"] = sum(item[1:] not in removed and not any(
        p == item[1] and a <= item[0] < b for p, a, b in intervals) for item in lost)
    if folder.name in ("9d", "10-lote-antigo"):
        node = int(meta["liderIsolado"])
        begin = int(meta["isolamentoLiderInicio"])
        end = int(meta["isolamentoLiderFim"])
        result["isolamento"] = dict(
            antigo=node, novo=int(meta["liderMaioritario"]),
            aceitesAntigo=sum(e["p"] == 1 and e["node"] == node and e["event"] == "RECEBIDO"
                             and e["fields"].get("origem") == "IN" and begin <= e["time"] <= end for e in ev),
            estadoSeguidor=meta["estadoSeguidorAntesReparacao"],
            termoSaudavelAntes=int(meta["termoSaudavelAntesSeguidor"]),
            lideresP1=[dict(no=e["node"], termo=int(e["fields"]["termo"]), instante=e["time"])
                       for e in ev if e["p"] == 1 and e["event"] == "LIDER"])
    if folder.name == "9e":
        result["rearranqueAteLiderMs"] = int(meta["rearranqueAteLiderMs"])
        stopped = {int(meta["liderTerminado"]), int(meta["seguidorTerminado"])}
        survivor = next(i for i in (1, 2, 3) if i not in stopped)
        result["sobrevivente"] = survivor
        result["termosSobrevivente"] = [int(e["fields"]["termo"]) for e in ev
                                       if e["p"] == 1 and e["node"] == survivor and e["event"] == "CANDIDATO"]
    return result

def stats(values):
    return dict(min=min(values), media=statistics.mean(values), max=max(values), n=len(values))

def main():
    folder = Path(sys.argv[1])
    runs = [r for path in sorted(folder.iterdir()) if path.is_dir() and (r := run(path))]
    normal = [r for r in runs if r["ensaio"].startswith("9")]
    random = [r for r in normal if r["ensaio"].startswith("9b-aleatorio")]
    fixed = [r for r in normal if r["ensaio"].startswith("9b-fixo")]
    crashes = [r for r in normal if r["ensaio"].startswith("9c-") or r["ensaio"] == "9a"]
    startup = [r for r in normal if r["ensaio"] == "9a" or r in random or r["ensaio"].startswith("9c-")]
    result = dict(execucoes=runs)
    result["arranqueAleatorioMs"] = stats([v for r in random for v in r["arranqueMs"] if v >= 0])
    result["arranqueFixoMs"] = stats([v for r in fixed for v in r["arranqueMs"] if v >= 0])
    result["arranque9a9cMs"] = stats([v for r in startup for v in r["arranqueMs"] if v >= 0])
    result["termosAleatorios"] = stats([v for r in random for v in r["termosPrimeiroLider"] if v >= 0])
    result["termosFixos"] = stats([v for r in fixed for v in r["termosPrimeiroLider"] if v >= 0])
    result["votosDivididosAleatorios"] = sum(sum(r["votosDivididos"]) for r in random)
    result["votosDivididosFixos"] = sum(sum(r["votosDivididos"]) for r in fixed)
    result["indisponibilidade9a9cMs"] = stats([f["ms"] for r in crashes for f in r["falhas"] if f["eraLider"] and f["ms"] >= 0])
    result["mediaPerdidasDuranteEleicao"] = statistics.mean(r["perdidasDuranteEleicao"] for r in crashes)
    result["mediaPerdidasPorTruncagem"] = statistics.mean(r["perdidasPorTruncagem"] for r in crashes)
    result["mediaRegistosTruncados"] = statistics.mean(r["registosTruncados"] for r in crashes)
    result["ensaiosNormais"] = len(normal)
    result["ensaiosFalhas"] = len(runs) - len(normal)
    (folder / "medidas.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    columns = ["ensaio", "raft", "causal", "leiturasEnviadas", "leiturasPerdidas", "perdidasDuranteEleicao",
               "perdidasPorTruncagem", "registosTruncados", "truncadosComOutraSeq", "termosComMultiplosLideres",
               "termosDiminuiram", "votosDuplos", "lotesObsoletosAplicados", "seqDivergentes", "errosDosNos"]
    with (folder / "medidas.csv").open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=columns, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(runs)
    print(json.dumps({k:v for k,v in result.items() if k != "execucoes"}, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
