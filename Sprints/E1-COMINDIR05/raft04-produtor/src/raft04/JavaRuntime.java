package raft04;

/** Resolve o executável real para o lançador PowerShell, evitando o wrapper Oracle javapath. */
public final class JavaRuntime {
    public static void main(String[] args) {
        System.out.println(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java.exe"));
    }
}
