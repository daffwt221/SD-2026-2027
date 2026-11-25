package tpf;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SeladorCheck {
    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tpf-selador-teste-");
        Path log = tmp.resolve("registos.log");
        Path dir = tmp.resolve("selados");
        Path key = tmp.resolve("master.key");
        RegistoLocal reg = new RegistoLocal(log);
        reg.acrescentar("utilizador", "arquivo", "Primeira mensagem");
        reg.acrescentar("utilizador", "arquivo", "Segunda mensagem");
        try (SeladorChunks s = new SeladorChunks(log, dir, key)) {
            if (s.selarPendentesAgora() != 1 || s.ultimoLsnSelado() != 2)
                throw new AssertionError("A primeira selagem falhou");
        }
        if (Files.size(key) != 32) throw new AssertionError("master.key inválida");
        try (SeladorChunks s = new SeladorChunks(log, dir, key)) {
            if (s.selarPendentesAgora() != 0 || s.ultimoLsnSelado() != 2)
                throw new AssertionError("Chunk repetido após reinício");
            reg.acrescentar("utilizador", "arquivo", "Terceira mensagem");
            if (s.selarPendentesAgora() != 1 || s.ultimoLsnSelado() != 3)
                throw new AssertionError("Continuidade do LSN falhou");
        }
        long bins, hdrs;
        try (var stream = Files.list(dir)) { bins = stream.filter(p -> p.toString().endsWith(".bin")).count(); }
        try (var stream = Files.list(dir)) { hdrs = stream.filter(p -> p.toString().endsWith(".hdr")).count(); }
        if (bins != 2 || hdrs != 2) throw new AssertionError("Ficheiros selados incorretos");
        System.out.println("SELADOR TESTS OK: chunks cifrados, persistência, reinício e LSN");
    }
}
