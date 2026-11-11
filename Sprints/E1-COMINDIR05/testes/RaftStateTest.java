package raft04;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Testes de segurança, persistência e dos próprios verificadores, sem frameworks. */
public final class RaftStateTest {
    private static int checks;
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static void write(Path path,String text)throws IOException{Files.writeString(path,text,StandardCharsets.UTF_8);}
    private static Properties properties(Path path)throws IOException {
        Properties p=new Properties();try(Reader r=Files.newBufferedReader(path,StandardCharsets.UTF_8)){p.load(r);}return p;
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory(args.length==0?Path.of("."):Path.of(args[0]),"raft-unit-");
        Path state=root.resolve("estado.txt");
        List<String> events=new ArrayList<>();
        RaftState voter=new RaftState(0,2,3,0,0,state,event->{
            events.add(event);
            if(event.startsWith("VOTO;")&&event.endsWith("concedido")) {
                try{require(Files.readString(state).contains(";"),"voto foi anunciado antes de persistir");}
                catch(IOException e){throw new UncheckedIOException(e);}
            }
        });
        require(voter.onVoteRequest(1,1,0,0,0,0).granted,"primeiro voto");
        require(Files.readString(state).strip().equals("1;1"),"estado persistido");
        RaftState restarted=new RaftState(0,2,3,0,0,state,events::add);
        require(!restarted.onVoteRequest(1,3,0,0,0,0).granted,"reinicio permite voto duplo");
        require(restarted.onVoteRequest(1,1,0,0,0,0).granted,"repetir o mesmo voto");
        require(restarted.onVoteRequest(2,3,0,0,0,0).granted,"novo termo abre novo voto");
        require(Files.readString(state).strip().equals("2;3"),"voto do novo termo");
        write(root.resolve("estado.txt.tmp"),"999;");
        RaftState ignoresTemporary=new RaftState(0,2,3,0,0,state,events::add);
        require(ignoresTemporary.term()==2,"carregou temporario incompleto");

        RaftState candidate=new RaftState(0,1,5,0,0,root.resolve("cinco.txt"),events::add);
        require(candidate.tick()==1,"candidatura inicial");
        require(!candidate.onVote(1,2,1,true),"maioria incorreta para grupo de cinco");
        require(!candidate.onVote(1,2,1,true),"duplicado contou duas vezes");
        require(candidate.onVote(1,3,1,true),"maioria de tres");
        require(candidate.role()==RaftState.Role.LIDER,"papel lider");
        require(!candidate.onBatch(0,2),"aceitou lote obsoleto");
        require(candidate.role()==RaftState.Role.LIDER,"lote obsoleto demitiu lider");
        require(candidate.onBatch(2,2),"nao reconheceu termo superior");
        require(candidate.role()==RaftState.Role.SEGUIDOR&&candidate.term()==2,"demissao por termo superior");
        require(Files.readString(root.resolve("cinco.txt")).strip().equals("2;0"),"voto antigo nao foi limpo");

        RaftState freshness=new RaftState(1,2,3,0,0,root.resolve("fresh.txt"),events::add);
        require(!freshness.onVoteRequest(2,1,99,1,5,2).granted,"seq maior venceu termo mais recente");
        require(freshness.onVoteRequest(4,3,1,3,99,2).granted,"termo de registo superior nao prevaleceu");
        require(!freshness.onVoteRequest(4,1,200,4,1,3).granted,"atualizacao sobrepos-se a voto ja dado");
        require(!freshness.onVoteRequest(3,3,200,3,1,3).granted,"pedido de termo antigo");
        freshness.onVote(5,1,1,false);
        require(freshness.term()==5&&freshness.role()==RaftState.Role.SEGUIDOR,"ignorou termo em voto dirigido a outro");

        Path causal=Files.createDirectory(root.resolve("causal"));
        String valid="1;1;S01;20.0;2026-11-11T10:00:00;1;1,0,0\n";
        for(int id=1;id<=3;id++)write(causal.resolve("no-p0-n"+id+".txt"),valid);
        try(PrintStream out=new PrintStream(Files.newOutputStream(causal.resolve("relatorio.txt")),true,"UTF-8")) {
            require(CausalCheck.check(3,causal,out)==0,"replicas validas rejeitadas");
        }
        require(properties(causal.resolve("causalcheck-resumo.properties")).getProperty("eventos").equals("1"),
                "CausalCheck contou tres copias como eventos distintos");
        write(causal.resolve("no-p0-n3.txt"),"1;1;S01;20.0;2026-11-11T10:00:00;1;0,0,0\n");
        try(PrintStream out=new PrintStream(Files.newOutputStream(causal.resolve("erro.txt")),true,"UTF-8")) {
            require(CausalCheck.check(3,causal,out)!=0,"CausalCheck nao detetou V[k] != seq");
        }

        Path audit=Files.createDirectory(root.resolve("audit"));
        for(int id=1;id<=3;id++){
            write(audit.resolve("no-p0-n"+id+".txt"),"");
            write(audit.resolve("estado-p0-n"+id+".txt"),"1;"+(id==2?2:1)+"\n");
        }
        write(audit.resolve("eventos-p0-n1.txt"),"1;TERMO;p0;arranque=true;termo=0\n"
                +"2;CANDIDATO;p0;termo=1\n5;LIDER;p0;termo=1;votos=[1, 3]\n");
        write(audit.resolve("eventos-p0-n2.txt"),"1;TERMO;p0;arranque=true;termo=0\n"
                +"2;CANDIDATO;p0;termo=1\n6;LIDER;p0;termo=1;votos=[2, 3]\n");
        write(audit.resolve("eventos-p0-n3.txt"),"1;TERMO;p0;arranque=true;termo=0\n"
                +"3;VOTO;p0;termo=1;candidato=n1;concedido\n4;VOTO;p0;termo=1;candidato=n2;concedido\n");
        try(PrintStream out=new PrintStream(Files.newOutputStream(audit.resolve("relatorio.txt")),true,"UTF-8")){
            require(RaftCheck.check(1,audit,audit.resolve("falhas.txt"),out)!=0,"RaftCheck aceitou dois lideres");
        }
        Properties result=properties(audit.resolve("raftcheck-resumo.properties"));
        require(result.getProperty("termosComMultiplosLideres").equals("1"),"contagem de dois lideres");
        require(result.getProperty("votosDuplos").equals("1"),"contagem de voto duplo");
        System.out.println("TESTES_UNITARIOS_OK="+checks);
        System.out.println("PASTA="+root);
    }
}
