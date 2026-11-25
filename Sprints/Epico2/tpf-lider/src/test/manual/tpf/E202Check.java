package tpf;

import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.stream.*;

public final class E202Check {
    private static void ok(boolean value, String error) { if (!value) throw new AssertionError(error); }
    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("e202-acceptance-");
        Path log = tmp.resolve("registos.log"), key = tmp.resolve("master.key"), dir = tmp.resolve("selados");
        RegistoLocal r = new RegistoLocal(log);
        try (SeladorChunks s = new SeladorChunks(log, dir, key)) {
            s.iniciar();
            r.acrescentar("aluno", "arquivo", "primeira mensagem");
            s.verificarAposRegisto();
            Thread.sleep(Wire.SELAGEM_MS + 800);
            ok(s.ultimoLsnSelado() == 1, "falhou selagem por tempo");
            for (int i=0;i<500;i++) {
                r.acrescentar("aluno", "arquivo", "RAJADA " + i + " " + "x".repeat(220));
                s.verificarAposRegisto();
            }
            s.selarPendentesAgora();
            ok(s.ultimoLsnSelado() == 501, "LSN final errado");
        }
        ok(Files.size(key) == 32, "master.key errada");
        ok(new RegistoLocal(log).ultimoLsn() == 501, "LSN não persistiu");
        ChunkCrypto crypto = ChunkCrypto.fromFile(key);
        List<Registo> result = new ArrayList<>();
        int chunks = 0;
        try (var arquivos = Files.list(dir)) {
            for (Path hdr : arquivos.filter(x->x.toString().endsWith(".hdr")).toList()) {
                Properties props = new Properties();
                try (var in=Files.newInputStream(hdr)) { props.load(in); }
                String id=hdr.getFileName().toString().replace(".hdr", "");
                byte[] ciphertext=Files.readAllBytes(dir.resolve(id+".bin"));
                ok(ChunkCrypto.sha256Hex(ciphertext).equals(id), "hash nome errado");
                ok((ciphertext.length-16)%Wire.PAD_BYTES==0, "padding errado");
                byte[] salt=Base64.getDecoder().decode(props.getProperty("salt"));
                byte[] nonce=Base64.getDecoder().decode(props.getProperty("nonce"));
                var sealed=new Sealed(id, Integer.parseInt(props.getProperty("keyVersion")), salt,nonce,ciphertext);
                result.addAll(Chunk.ler(crypto.open(sealed)));
                chunks++;
            }
        }
        result.sort(Comparator.comparingLong(Registo::lsn));
        ok(chunks>=3, "rajada não produziu vários chunks");
        ok(result.size()==501,"leitura inversa falhou: " + result.size());
        for (int i=0;i<result.size();i++) ok(result.get(i).lsn()==i+1,"LSN descontínuo");
        String eventos=Files.readString(tmp.resolve("eventos-lider.txt"));
        ok(eventos.contains(";REGISTO;") && eventos.contains(";SELADO;"),"eventos em falta");
        byte[] wrong=Files.readAllBytes(key); wrong[0]^=1;
        Path wrongKey=tmp.resolve("wrong.key"); Files.write(wrongKey,wrong);
        // Testar que uma master.key de tamanho errado impede o arranque.
        Path shortKey=tmp.resolve("short.key"); Files.write(shortKey,new byte[3]);
        try (SeladorChunks unused=new SeladorChunks(log,tmp.resolve("out-short"),shortKey)) {
            throw new AssertionError("aceitou chave inválida");
        } catch (java.io.IOException expected) { }
        System.out.println("E2-02 CHECK OK: " + result.size() + " registos, " + chunks + " chunks, tempo/tamanho, integridade, leitura inversa e eventos");
    }
}
