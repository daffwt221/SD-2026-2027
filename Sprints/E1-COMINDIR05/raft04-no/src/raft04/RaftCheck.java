package raft04;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import static raft04.AuditFiles.*;

/** Auditoria offline: os dados de entrada nunca são alterados. */
public final class RaftCheck {
    private RaftCheck() { }
    private static final class Round {
        final Set<Integer> candidates=new TreeSet<>(),leaders=new TreeSet<>();
        final Map<Integer,Set<Integer>> votes=new TreeMap<>();
        long firstCandidate=Long.MAX_VALUE,firstLeader=Long.MAX_VALUE;
        void vote(int candidate,int voter){votes.computeIfAbsent(candidate,k->new TreeSet<>()).add(voter);}
    }

    public static void main(String[] args) {
        if(args.length<2||args.length>3) {
            System.err.println("Uso: RaftCheck <N> <pasta> [falhas.txt]");System.exit(1);
        }
        try {
            Path folder=Path.of(args[1]);
            Path failures=args.length==3?Path.of(args[2]):folder.resolve("falhas.txt");
            if(!failures.isAbsolute()&&args.length==3&&!Files.exists(failures))failures=folder.resolve(args[2]);
            System.exit(check(Integer.parseInt(args[0]),folder,failures,System.out));
        } catch(IOException|RuntimeException e){System.err.println("ERRO: "+e.getMessage());System.exit(1);}
    }

    static int check(int n,Path folder,Path failures,PrintStream out)throws IOException {
        if(n<1||!Files.isDirectory(folder))throw new IllegalArgumentException("N/pasta inválidos");
        List<NodeLog> logs=nodes(n,folder);
        if(logs.isEmpty())throw new IllegalArgumentException("Nenhum ficheiro no-p*-n*.txt");
        List<Event> events=events(n,folder,false);
        List<Event> monitorEvents=events(n,folder,true);
        List<Heartbeat> heartbeats=heartbeats(n,folder);
        List<Persistent> states=states(n,folder);
        List<Input> inputs=inputs(n,folder);
        Properties summary=new Properties();
        Map<Integer,TreeMap<Long,Round>> elections=new TreeMap<>();
        Map<String,Set<Integer>> choices=new TreeMap<>();
        Map<Integer,Event> finalLeader=new HashMap<>();
        long[] start=new long[n],firstLeader=new long[n];
        Arrays.fill(start,Long.MAX_VALUE);Arrays.fill(firstLeader,Long.MAX_VALUE);
        int obsolete=0,nodeErrors=0;
        for(Event event:events) {
            int p=event.partition;
            if(event.type.equals("TERMO")&&"true".equals(event.fields.get("arranque")))
                start[p]=Math.min(start[p],event.time);
            if(event.type.equals("ERRO"))nodeErrors++;
            if(event.type.equals("RECEBIDO")&&"LOTE".equals(event.fields.get("origem"))
                    &&event.number("termo",-1)<event.number("atual",-1))obsolete++;
            if(!(event.type.equals("CANDIDATO")||event.type.equals("VOTO")||event.type.equals("LIDER")))continue;
            long term=event.number("termo",-1);
            if(term<0)throw new IllegalArgumentException(event.source+": evento sem termo");
            Round round=elections.computeIfAbsent(p,k->new TreeMap<>()).computeIfAbsent(term,k->new Round());
            if(event.type.equals("CANDIDATO")) {
                round.candidates.add(event.node);round.firstCandidate=Math.min(round.firstCandidate,event.time);
                round.vote(event.node,event.node);
                choices.computeIfAbsent(p+";"+term+";"+event.node,k->new TreeSet<>()).add(event.node);
            } else if(event.type.equals("VOTO")&&event.fields.containsKey("concedido")) {
                int candidate=Integer.parseInt(event.fields.get("candidato").replace("n",""));
                round.vote(candidate,event.node);
                choices.computeIfAbsent(p+";"+term+";"+event.node,k->new TreeSet<>()).add(candidate);
            } else if(event.type.equals("LIDER")) {
                round.leaders.add(event.node);round.firstLeader=Math.min(round.firstLeader,event.time);
                firstLeader[p]=Math.min(firstLeader[p],event.time);
                Event previous=finalLeader.get(p);
                if(previous==null||term>previous.number("termo",-1)
                        ||(term==previous.number("termo",-1)&&event.time>previous.time))finalLeader.put(p,event);
            }
        }
        int multiple=0,withoutMajority=0;
        long globalStart=Arrays.stream(start).min().orElse(Long.MAX_VALUE);
        out.println("ELEICOES:");
        for(int p=0;p<n;p++) {
            final int currentPartition=p;
            int noLeader=0,split=0;
            for(Map.Entry<Long,Round> entry:elections.getOrDefault(p,new TreeMap<>()).entrySet()) {
                Round round=entry.getValue();
                if(round.leaders.size()>1)multiple++;
                if(round.leaders.isEmpty()){noLeader++;if(round.candidates.size()>1)split++;}
                for(int leader:round.leaders)if(round.votes.getOrDefault(leader,Set.of()).size()<=Wire.GRUPO/2)withoutMajority++;
                long duration=round.firstLeader==Long.MAX_VALUE||round.firstCandidate==Long.MAX_VALUE
                        ?-1:round.firstLeader-round.firstCandidate;
                out.println("p"+p+" termo="+entry.getKey()+" candidatos="+round.candidates+" votos="+round.votes
                        +" lideres="+round.leaders+" duracaoMs="+duration
                        +(round.leaders.isEmpty()?" sem lider (voto dividido/falta de quorum)":""));
            }
            long startup=firstLeader[p]==Long.MAX_VALUE||globalStart==Long.MAX_VALUE?-1:firstLeader[p]-globalStart;
            long termsToFirst=firstLeader[p]==Long.MAX_VALUE?-1:elections.get(p).entrySet().stream()
                    .filter(e->e.getValue().firstCandidate<=firstLeader[currentPartition]).count();
            summary.setProperty("primeiroLider.p"+p+".ms",Long.toString(startup));
            summary.setProperty("termosAtePrimeiroLider.p"+p,Long.toString(termsToFirst));
            summary.setProperty("termosSemLider.p"+p,Integer.toString(noLeader));
            summary.setProperty("votosDivididos.p"+p,Integer.toString(split));
            out.println("p"+p+" primeiroLiderMs="+startup+" termosAtePrimeiro="+termsToFirst
                    +" termosSemLider="+noLeader+" votosDivididosEfetivos="+split);
        }

        Map<String,Long> lastObserved=new HashMap<>(),highestObserved=new HashMap<>();
        int decreases=0,missingState=0;
        Set<String> active=Set.of("TERMO","CANDIDATO","VOTO","LIDER","DEMISSAO");
        for(Event event:events) {
            if(!active.contains(event.type))continue;
            long term=activeTerm(event);
            if(term<0)continue;
            String key=nodeKey(event.partition,event.node);
            if(term<lastObserved.getOrDefault(key,-1L)) {
                decreases++;out.println("TERMO_DIMINUIU: "+event.source+":"+event.line+" -> "+term);
            }
            lastObserved.put(key,term);
            highestObserved.merge(key,term,Math::max);
        }
        Map<String,Persistent> byState=new HashMap<>();
        for(Persistent state:states)byState.put(nodeKey(state.partition,state.node),state);
        for(Map.Entry<String,Long> observed:highestObserved.entrySet()) {
            Persistent state=byState.get(observed.getKey());
            if(observed.getValue()>0&&(state==null||state.term<observed.getValue())) {
                missingState++;out.println("ESTADO_PERSISTENTE_AUSENTE_ATRASADO: "+observed.getKey());
            }
        }
        int doubleVotes=(int)choices.values().stream().filter(v->v.size()>1).count();
        for(Map.Entry<String,Set<Integer>> choice:choices.entrySet())
            if(choice.getValue().size()>1)out.println("VOTO_DUPLO: p;termo;eleitor="+choice.getKey()+" candidatos="+choice.getValue());
        out.println("SEGURANCA: termosComMaisDeUmLider="+multiple+" termosDiminuiram="+decreases
                +" votosDuplos="+doubleVotes+" lideresSemMaioriaAuditada="+withoutMajority
                +" estadosAusentesAtrasados="+missingState+" lotesObsoletosAplicados="+obsolete);

        int divergent=0;
        out.println("COINCIDENCIA FINAL:");
        for(int p=0;p<n;p++) {
            final int partition=p;
            List<NodeLog> group=logs.stream().filter(l->l.partition==partition).collect(Collectors.toList());
            if(group.isEmpty()){out.println("p"+p+": nao incluida neste ensaio");continue;}
            long min=group.stream().mapToLong(NodeLog::lastSeq).min().orElse(0);
            List<Long> differences=new ArrayList<>();
            for(long seq=1;seq<=min;seq++) {
                String reference=null;
                for(NodeLog log:group) {
                    String value=log.records.get((int)seq-1).toLine();
                    if(reference==null)reference=value;
                    else if(!reference.equals(value)){differences.add(seq);break;}
                }
            }
            divergent+=differences.size();
            out.println("p"+p+" ficheiros="+group.size()+"/3 ultimos="+group.stream()
                    .map(l->"n"+l.id+":"+l.lastSeq()).collect(Collectors.joining(","))
                    +" comparadosAte="+min+" seqDivergentes="+differences);
        }

        Map<String,List<Input>> byInput=inputs.stream().collect(Collectors.groupingBy(Input::key,LinkedHashMap::new,Collectors.toList()));
        Set<String> available=new HashSet<>();
        for(NodeLog log:logs)for(SensorRecord record:log.records)available.add(key(log.partition,record));
        List<Input> lost=new ArrayList<>();
        int ambiguous=0;
        out.println("LEITURAS PERDIDAS (presenca no registo final de qualquer no):");
        for(Map.Entry<String,List<Input>> entry:byInput.entrySet()) {
            if(!available.contains(entry.getKey()))lost.addAll(entry.getValue());
            else if(entry.getValue().size()>1) {
                ambiguous+=entry.getValue().size();
                out.println("AMBIGUO sem identificador de leitura: "+entry.getKey()+" envios="+entry.getValue().size());
            }
        }
        lost.sort(Comparator.comparingLong(i->i.time));
        for(Input input:lost)out.println("PERDIDA linha="+input.line+" "+input.text());
        out.println("leiturasPerdidasConfirmadas="+lost.size()+" leiturasAmbiguas="+ambiguous);

        int discarded=0,otherSeq=0,truncations=0,unknownDiscarded=0;
        out.println("TRUNCAGENS:");
        for(Event event:events) {
            if(!event.type.equals("TRUNCADO"))continue;
            truncations++;
            int count=(int)event.number("descartados",0);discarded+=count;
            Event leader=finalLeader.get(event.partition);
            List<SensorRecord> finalRecords=leader==null?List.of():logs.stream()
                    .filter(l->l.partition==event.partition&&l.id==leader.node)
                    .findFirst().map(l->l.records).orElse(List.of());
            int found=0;
            String encoded=event.fields.get("registos64");
            if(encoded==null)unknownDiscarded+=count;
            else {
                String payload=new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8);
                for(String line:payload.split("\\R"))if(!line.isBlank()) {
                    SensorRecord removed=SensorRecord.fromLine(line);
                    if(finalRecords.stream().anyMatch(r->r.getSeq()!=removed.getSeq()
                            &&key(event.partition,r).equals(key(event.partition,removed))))found++;
                }
            }
            otherSeq+=found;
            out.println(event.time+" p"+event.partition+" n"+event.node+" de="+event.number("de",0)
                    +" descartados="+count+" termos="+event.fields.get("termos")
                    +" encontradosComOutraSeq="+(encoded==null?"indisponivel":found));
        }

        int failureIndex=0;
        out.println("FALHAS E INDISPONIBILIDADE:");
        for(String line:lines(failures)) {
            String[] fields=line.split(";",-1);
            if(fields.length!=3||!fields[0].matches("p\\d+")||!fields[1].matches("n\\d+"))
                throw new IllegalArgumentException("falhas.txt: p<k>;n<i>;instante esperado");
            int p=Integer.parseInt(fields[0].substring(1)),id=Integer.parseInt(fields[1].substring(1));
            node(p,id,n);
            long instant=Long.parseLong(fields[2]);
            Heartbeat previous=null;
            for(Heartbeat heartbeat:heartbeats)if(heartbeat.partition==p&&heartbeat.time<=instant)previous=heartbeat;
            long oldTerm=previous==null?-1:previous.term;
            Heartbeat next=null;
            for(Heartbeat heartbeat:heartbeats)if(heartbeat.partition==p&&heartbeat.time>=instant&&heartbeat.term>oldTerm){next=heartbeat;break;}
            boolean wasLeader=previous!=null&&previous.node==id;
            long unavailable=wasLeader?(next==null?-1:next.time-instant):0;
            int inInterval=0;
            out.println("falha=p"+p+";n"+id+";"+instant+" eraLider="+wasLeader+" termoAnterior="+oldTerm
                    +" primeiroLoteSuperior="+(next==null?"ausente":next.time)+" indisponibilidadeMs="+unavailable);
            if(wasLeader&&next!=null)for(Input input:inputs)
                if(input.partition==p&&input.time>=instant&&input.time<next.time) {
                    inInterval++;out.println("DURANTE_ELEICAO "+input.text());
                }
            summary.setProperty("falha."+failureIndex+".ms",Long.toString(unavailable));
            summary.setProperty("falha."+failureIndex+".leituras",Integer.toString(inInterval));
            summary.setProperty("falha."+failureIndex+".eraLider",Boolean.toString(wasLeader));
            failureIndex++;
        }
        Map<String,Long> monitorCounts=monitorEvents.stream().collect(Collectors.groupingBy(e->e.type,TreeMap::new,Collectors.counting()));
        out.println("MONITORES: "+monitorCounts);
        out.println("ERROS DOS NOS: "+nodeErrors);
        put(summary,"termosComMultiplosLideres",multiple);put(summary,"termosDiminuiram",decreases);
        put(summary,"votosDuplos",doubleVotes);put(summary,"lideresSemMaioria",withoutMajority);
        put(summary,"estadosAusentesAtrasados",missingState);put(summary,"lotesObsoletosAplicados",obsolete);
        put(summary,"seqDivergentes",divergent);put(summary,"leiturasEnviadas",inputs.size());
        put(summary,"leiturasPerdidas",ambiguous==0?lost.size():-1);put(summary,"leiturasAmbiguas",ambiguous);
        put(summary,"truncagens",truncations);put(summary,"registosTruncados",discarded);
        put(summary,"truncadosComOutraSeq",unknownDiscarded==0?otherSeq:-1);put(summary,"errosDosNos",nodeErrors);
        put(summary,"falhas",failureIndex);
        int safety=multiple+decreases+doubleVotes+withoutMajority+missingState+obsolete+divergent;
        put(summary,"violacoes",safety);
        try(Writer writer=Files.newBufferedWriter(folder.resolve("raftcheck-resumo.properties"),StandardCharsets.UTF_8)) {
            summary.store(writer,"Medidas extraidas dos ficheiros do ensaio");
        }
        out.println(safety==0?"RESULTADO: SEM VIOLACOES AUDITADAS.":"RESULTADO: FALHOU ("+safety+" violacoes).");
        return safety==0?0:2;
    }

    private static void put(Properties properties,String key,int value){properties.setProperty(key,Integer.toString(value));}
    private static long activeTerm(Event event) {
        if(event.fields.containsKey("termo"))return event.number("termo",-1);
        Matcher matcher=Pattern.compile("(\\d+)->(\\d+)").matcher(event.detail);
        return matcher.find()?Long.parseLong(matcher.group(2)):-1;
    }
}
