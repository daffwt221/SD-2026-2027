package raft04;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Ensaios com processos Java reais e a API Kubo indicada, sem simulador de PubSub. */
public final class RaftExperiments {
    static Path root,base;
    static String api;
    private static final int N=3;
    static final class RunLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        RunLock()throws IOException {
            Files.createDirectories(root.resolve("resultados"));
            channel=FileChannel.open(root.resolve("resultados/ensaios.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            lock=channel.tryLock();
            if(lock==null){channel.close();throw new IOException("Já está outro ensaio a utilizar os mesmos tópicos/portos");}
            try {
                for(int p=0;p<N;p++)for(int id=1;id<=3;id++)
                    try(java.net.ServerSocket probe=new java.net.ServerSocket(Wire.nodePort(p,id),1,java.net.InetAddress.getLoopbackAddress())) { }
            } catch(IOException e){close();throw new IOException("Os portos dos nós estão ocupados; terminar a sessão anterior",e);}
        }
        @Override public void close()throws IOException {lock.release();channel.close();}
    }
    static final class Beat {
        int node; long term,hb,time,last;
        Beat(int node,String[] f){this.node=node;term=Long.parseLong(f[0]);hb=Long.parseLong(f[1]);time=Long.parseLong(f[2]);last=Long.parseLong(f[3]);}
    }
    static String text(Path path){
        try{return Files.exists(path)?Files.readString(path,StandardCharsets.UTF_8):"";}
        catch(IOException e){return "";}
    }
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void await(BooleanSupplier condition,long timeout,String message)throws Exception{
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
        while(!condition.getAsBoolean()){
            if(System.nanoTime()>end)throw new AssertionError("Timeout: "+message);
            Thread.sleep(25);
        }
    }
    private static String javaBin(){return Path.of(System.getProperty("java.home"),"bin","java.exe").toString();}
    private static String classpath(String role){return root.resolve("raft04-"+role).resolve("out").toString();}

    static final class Context implements AutoCloseable {
        final Path folder;
        final Map<String,Process> active=new LinkedHashMap<>();
        final List<Process> owned=new ArrayList<>();
        final Properties meta=new Properties();
        final List<String> options=new ArrayList<>();
        Writer input;
        String nodeClasspath=classpath("no");
        long barrier;
        Context(String name)throws IOException{
            folder=base.resolve(name);
            if(Files.exists(folder))throw new IOException("A pasta já existe; usar uma pasta de resultados nova: "+folder);
            Files.createDirectories(folder);
            meta.setProperty("ensaio",name);meta.setProperty("api",api);
            meta.setProperty("inicio",Long.toString(System.currentTimeMillis()));
            System.out.println("INICIO "+name);
        }
        Process start(String name,String role,String main,List<String> args)throws IOException{
            List<String> command=new ArrayList<>(List.of(javaBin(),"-Xms16m","-Xmx96m",
                    "-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8","-Draft04.api="+api));
            command.addAll(options);
            if(barrier>0&&main.equals("PartitionNode"))command.add("-Draft04.startAt="+barrier);
            command.add("-cp");
            String cp=role.equals("no")?nodeClasspath:classpath(role);
            if(barrier>0&&main.equals("PartitionNode")){
                cp+=File.pathSeparator+root.resolve("testes/out");main="BarrierNode";
            }
            command.add(cp);command.add("raft04."+main);command.addAll(args);
            Process process=new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(folder.resolve("consola-"+name+".txt").toFile())).start();
            active.put(name,process);owned.add(process);
            Files.writeString(folder.resolve("processos.txt"),System.currentTimeMillis()+";"+name+";"+process.pid()+"\n",
                    StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            return process;
        }
        void node(int p,int id)throws IOException{start("n"+p+"-"+id,"no","PartitionNode",List.of(""+p,""+id,""+N));}
        void allNodes(int seed)throws IOException{
            List<int[]> order=new ArrayList<>();
            for(int p=0;p<N;p++)for(int id=1;id<=3;id++)order.add(new int[]{p,id});
            Collections.shuffle(order,new Random(seed));
            for(int[] node:order)node(node[0],node[1]);
            meta.setProperty("ordem",order.stream().map(a->"p"+a[0]+"n"+a[1]).reduce((a,b)->a+","+b).orElse(""));
        }
        void pp(boolean waitAll)throws Exception{
            start("processador","processador","AlarmProcessor",List.of(""+N,api));
            Process producer=start("produtor","produtor","Producer",List.of(""+N,api));
            input=new BufferedWriter(new OutputStreamWriter(producer.getOutputStream(),StandardCharsets.UTF_8));
            if(waitAll)await(()->{
                for(int p=0;p<N;p++)
                    if(!text(folder.resolve("monitor-produtor.txt")).contains("NOVO_LIDER;p"+p+";")
                            ||!text(folder.resolve("monitor-processador.txt")).contains("NOVO_LIDER;p"+p+";"))return false;
                return true;
            },15000,"monitores das três partições");
        }
        void send(String line)throws IOException{input.write(line+"\n");input.flush();}
        int sent(){return (int)text(folder.resolve("produtor.txt")).lines().filter(s->!s.isBlank()).count();}
        void reading(String command)throws Exception{
            int count=sent();send(command);await(()->sent()>count,10000,"envio "+command);
        }
        Beat beat(int p){
            Beat best=null;
            for(int id=1;id<=3;id++){
                String content=text(folder.resolve("hb-p"+p+"-n"+id+".txt"));
                for(String line:content.split("\\R"))try{
                    String[] f=line.split(";");if(f.length!=7)continue;
                    Beat beat=new Beat(id,f);
                    if(best==null||beat.term>best.term||(beat.term==best.term&&beat.time>best.time))best=beat;
                }catch(NumberFormatException ignored){}
            }
            return best;
        }
        boolean leaders(){for(int p=0;p<N;p++)if(beat(p)==null)return false;return true;}
        void waitLeaders()throws Exception{await(this::leaders,45000,"primeiros líderes");}
        long seq(int p,int id){
            String data=text(folder.resolve("no-p"+p+"-n"+id+".txt"));
            long last=0;
            for(String line:data.split("\\R"))try{
                String[] f=line.split(";",-1);if(f.length==7)last=Long.parseLong(f[0]);
            }catch(NumberFormatException ignored){}
            return last;
        }
        String events(int p,int id){return text(folder.resolve("eventos-p"+p+"-n"+id+".txt"));}
        boolean equal(int p){
            String first=null;
            for(int id=1;id<=3;id++){
                Path path=folder.resolve("no-p"+p+"-n"+id+".txt");
                if(!Files.exists(path))return false;
                String value=text(path);
                if(first==null)first=value;else if(!first.equals(value))return false;
            }
            return true;
        }
        void converge()throws Exception{
            await(()->{for(int p=0;p<N;p++)if(!equal(p))return false;return true;},25000,"coincidência final");
        }
        long stop(int p,int id,boolean failure)throws Exception{
            long instant=System.currentTimeMillis();
            if(failure)Files.writeString(folder.resolve("falhas.txt"),"p"+p+";n"+id+";"+instant+"\n",
                    StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            stop("n"+p+"-"+id);
            return instant;
        }
        void stop(String name)throws Exception{
            Process process=active.remove(name);
            if(process!=null&&process.isAlive()){
                process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);
            }
        }
        Path marker(int p,int id){return folder.resolve("isolar-p"+p+"-n"+id);}
        void isolate(int p,int id)throws IOException{
            Files.writeString(marker(p,id),"",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            Files.writeString(folder.resolve("particoes.txt"),System.currentTimeMillis()+";INICIO;p"+p+";n"+id+"\n",
                    StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }
        void repair(int p,int id)throws IOException{
            Files.delete(marker(p,id));
            Files.writeString(folder.resolve("particoes.txt"),System.currentTimeMillis()+";FIM;p"+p+";n"+id+"\n",
                    StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }
        void until(long instant)throws InterruptedException{
            while(true){long remaining=instant-System.currentTimeMillis();if(remaining<=0)return;Thread.sleep(Math.min(100,remaining));}
        }
        void finish(boolean expectValid)throws Exception{
            close();
            int raft,causal;
            try(PrintStream out=new PrintStream(Files.newOutputStream(folder.resolve("RaftCheck.txt")),true,"UTF-8")){
                raft=RaftCheck.check(N,folder,folder.resolve("falhas.txt"),out);
            }
            try(PrintStream out=new PrintStream(Files.newOutputStream(folder.resolve("CausalCheck.txt")),true,"UTF-8")){
                causal=CausalCheck.check(N,folder,out);
            }
            meta.setProperty("raftCheck",Integer.toString(raft));meta.setProperty("causalCheck",Integer.toString(causal));
            meta.setProperty("estado","CONCLUIDO");
            save();
            System.out.println("FIM "+meta.getProperty("ensaio")+" RaftCheck="+raft+" CausalCheck="+causal);
            if(expectValid)require(raft==0&&causal==0,"Verificadores falharam em "+folder);
        }
        void save()throws IOException{
            meta.setProperty("fim",Long.toString(System.currentTimeMillis()));
            try(Writer out=Files.newBufferedWriter(folder.resolve("ensaio.properties"),StandardCharsets.UTF_8)){meta.store(out,"Ensaio real com Kubo");}
        }
        @Override public void close(){
            if(input!=null)try{input.close();}catch(IOException ignored){}
            for(Process process:owned)if(process.isAlive()){
                process.destroyForcibly();
                try{process.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            }
            if(!meta.containsKey("estado")){
                meta.setProperty("estado","INTERROMPIDO");
                try{save();}catch(IOException e){System.err.println(e);}
            }
        }
    }

    private static void figure(String name,boolean smoke)throws Exception{
        try(Context c=new Context(name)){
            if(smoke)c.options.addAll(List.of("-Draft04.periodo=500","-Draft04.eleicaoMin=1500","-Draft04.eleicaoMax=2500"));
            c.allNodes(12);c.waitLeaders();c.pp(true);
            Beat old=c.beat(1);c.meta.setProperty("liderAnterior",""+old.node);
            c.reading("S02 20.1");c.reading("S05 23.7");
            await(()->{for(int id=1;id<=3;id++)if(c.seq(1,id)<2)return false;return true;},10000,"duas leituras replicadas");
            long hb=c.beat(1).time;
            await(()->c.beat(1).time>hb,5000,"janela após um lote");
            c.reading("S02 20.4");
            await(()->c.events(1,old.node).contains(";seq=3;registo64="),5000,"terceira leitura persistida");
            long failure=c.stop(1,old.node,true);
            c.meta.setProperty("falha",Long.toString(failure));
            c.reading("S05 23.9");
            await(()->c.beat(1)!=null&&c.beat(1).term>old.term,30000,"eleição após falha");
            Beat next=c.beat(1);c.meta.setProperty("novoLider",""+next.node);
            c.reading("S02 20.6");
            if(smoke){c.reading("S05 20.7");c.reading("S02 20.8");}
            await(()->c.seq(1,next.node)>=(smoke?5:3),10000,"leituras do novo líder");
            c.node(1,old.node);
            await(()->c.events(1,old.node).contains(";TRUNCADO;"),15000,"truncagem do antigo líder");
            c.converge();
            c.finish(true);
        }
    }

    private static void startup()throws Exception{
        for(boolean fixed:new boolean[]{false,true})for(int run=1;run<=(fixed?5:10);run++){
            String name="9b-"+(fixed?"fixo":"aleatorio")+"-"+run;
            try(Context c=new Context(name)){
                if(fixed){
                    c.options.add("-Draft04.eleicaoMin=6000");c.options.add("-Draft04.eleicaoMax=6000");
                    c.barrier=System.currentTimeMillis()+6000;
                }
                c.allNodes(100+run);c.pp(false);
                long end=System.currentTimeMillis()+60000;
                while(!c.leaders()&&System.currentTimeMillis()<end)Thread.sleep(25);
                c.meta.setProperty("observacaoCensurada",Boolean.toString(!c.leaders()));
                c.meta.setProperty("limiteObservacaoMs","60000");
                c.finish(true);
            }
        }
    }

    private static void crashBursts(int first,int last)throws Exception{
        for(int run=first;run<=last;run++)try(Context c=new Context("9c-"+run)){
            c.allNodes(200+run);c.waitLeaders();c.pp(true);
            Beat old=c.beat(1);c.send("rajada 300 10");
            await(()->c.sent()>=25,15000,"início da rajada");
            long failure=c.stop(1,old.node,true);
            c.meta.setProperty("liderAnterior",""+old.node);c.meta.setProperty("falha",""+failure);
            await(()->c.sent()==300,45000,"300 leituras enviadas");
            c.until(failure+30000);c.node(1,old.node);
            await(()->c.beat(1)!=null&&c.beat(1).term>old.term,30000,"novo líder");
            c.converge();c.finish(true);
        }
    }

    private static void partitionNetwork()throws Exception{
        try(Context c=new Context("9d")){
            networkScenario(c);
            c.finish(true);
        }
    }

    static void networkScenario(Context c)throws Exception{
            c.allNodes(300);c.waitLeaders();c.pp(true);
            Beat old=c.beat(1);c.send("rajada 300 10");
            await(()->c.sent()>=25,15000,"início da rajada");
            captureBatch(c,1,old);
            c.isolate(1,old.node);long cut=System.currentTimeMillis();
            c.meta.setProperty("liderIsolado",""+old.node);c.meta.setProperty("isolamentoLiderInicio",""+cut);
            await(()->c.sent()==300,45000,"300 leituras enviadas");
            await(()->c.beat(1)!=null&&c.beat(1).term>old.term,30000,"maioria elege outro líder");
            c.meta.setProperty("liderMaioritario",""+c.beat(1).node);
            c.until(cut+30000);c.repair(1,old.node);
            c.meta.setProperty("isolamentoLiderFim",""+System.currentTimeMillis());
            c.converge();
            Beat leader=c.beat(1);
            int follower=leader.node==1?2:1;
            c.isolate(1,follower);long followerCut=System.currentTimeMillis();
            c.meta.setProperty("seguidorIsolado",""+follower);c.meta.setProperty("isolamentoSeguidorInicio",""+followerCut);
            c.meta.setProperty("termoSaudavelAntesSeguidor",""+leader.term);
            c.until(followerCut+60000);
            String state=text(c.folder.resolve("estado-p1-n"+follower+".txt")).strip();
            c.meta.setProperty("estadoSeguidorAntesReparacao",state);
            c.repair(1,follower);c.meta.setProperty("isolamentoSeguidorFim",""+System.currentTimeMillis());
            await(()->c.beat(1).term>leader.term,30000,"termo do seguidor perturba liderança");
            c.converge();
    }

    static void captureBatch(Context c,int p,Beat old)throws Exception{
        String[] h=text(c.folder.resolve("hb-p"+p+"-n"+old.node+".txt")).strip().split("\\R");
        String[] f=h[h.length-1].split(";");
        long last=Long.parseLong(f[3]);int count=Integer.parseInt(f[5]);
        List<SensorRecord> records=new ArrayList<>();
        for(String line:text(c.folder.resolve("no-p"+p+"-n"+old.node+".txt")).split("\\R")){
            if(line.isBlank())continue;
            SensorRecord r=SensorRecord.fromLine(line);
            if(r.getSeq()>last-count&&r.getSeq()<=last)records.add(r);
        }
        Batch batch=new Batch(Long.parseLong(f[0]),old.node,"127.0.0.1",Wire.nodePort(p,old.node),
                Long.parseLong(f[1]),last,Long.parseLong(f[4]),records);
        Files.writeString(c.folder.resolve("lote-antigo.txt"),batch.encode(),StandardCharsets.UTF_8);
    }

    private static void noQuorum()throws Exception{
        try(Context c=new Context("9e")){
            c.allNodes(400);c.waitLeaders();c.pp(true);
            c.reading("S02 21.0");
            await(()->{for(int id=1;id<=3;id++)if(c.seq(1,id)<1)return false;return true;},10000,"leitura inicial replicada");
            c.converge();
            Beat old=c.beat(1);int other=old.node==1?2:1;
            c.stop(1,old.node,true);long failure=c.stop(1,other,true);
            c.meta.setProperty("liderTerminado",""+old.node);c.meta.setProperty("seguidorTerminado",""+other);
            for(int i=0;i<30;i++){c.reading("S02 "+Double.toString(22.0+i/1000.0));c.until(failure+(i+1)*1000L);}
            long restart=System.currentTimeMillis();c.node(1,old.node);c.meta.setProperty("rearranqueUmNo",""+restart);
            await(()->c.beat(1).term>old.term,30000,"maioria restaurada");
            c.meta.setProperty("rearranqueAteLiderMs",""+(c.beat(1).time-restart));
            c.reading("S02 23.0");
            await(()->c.seq(1,c.beat(1).node)>=2,10000,"leitura após recuperar maioria");
            Thread.sleep(2500);c.finish(true);
        }
    }

    public static void main(String[] args)throws Exception{
        if(args.length<3||args.length>4)throw new IllegalArgumentException("RaftExperiments <smoke|9a|9b|9c|9d|9e|all> <raizSprint> <pasta> [api]");
        root=Path.of(args[1]).toAbsolutePath().normalize();base=Path.of(args[2]).toAbsolutePath().normalize();
        api=args.length==4?args[3]:"http://127.0.0.1:5001/api/v0";
        Files.createDirectories(base);
        String suite=args[0];
        try(RunLock lock=new RunLock()) {
        if(suite.equals("smoke"))figure("smoke",true);
        else {
            if(suite.equals("9a")||suite.equals("all"))figure("9a",false);
            if(suite.equals("9b")||suite.equals("all"))startup();
            if(suite.equals("9c")||suite.equals("all"))crashBursts(1,3);
            if(suite.equals("9c3"))crashBursts(3,3);
            if(suite.equals("9d")||suite.equals("all"))partitionNetwork();
            if(suite.equals("9e")||suite.equals("all"))noQuorum();
        }
        System.out.println("ENSAIOS_TERMINADOS");
        }
    }
}
