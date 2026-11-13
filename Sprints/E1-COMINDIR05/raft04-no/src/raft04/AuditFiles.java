package raft04;

import java.io.IOException;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Leitura dos ficheiros de observação, sem participar no protocolo. */
final class AuditFiles {
    static final Pattern NODE = Pattern.compile("no-p(\\d+)-n(\\d+)\\.txt");
    static final Pattern EVENTS = Pattern.compile("eventos-p(\\d+)-n(\\d+)\\.txt");
    static final Pattern STATE = Pattern.compile("estado-p(\\d+)-n(\\d+)\\.txt");
    static final Pattern HB = Pattern.compile("hb-p(\\d+)-n(\\d+)\\.txt");

    static List<String> lines(Path file) throws IOException {
        if (!Files.exists(file)) return List.of();
        byte[] data = Files.readAllBytes(file);
        Charset charset = data.length >= 2 && data[0] == (byte)0xff && data[1] == (byte)0xfe
                ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8;
        String text = new String(data, charset);
        if (text.startsWith("\ufeff")) text = text.substring(1);
        return text.lines().filter(line -> !line.isBlank()).collect(Collectors.toList());
    }

    static List<Path> files(Path folder) throws IOException {
        try (Stream<Path> paths = Files.list(folder)) {
            return paths.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
    }

    static void node(int partition, int id, int n) {
        if (partition < 0 || partition >= n || id < 1 || id > Wire.GRUPO)
            throw new IllegalArgumentException("Identificador fora de N/G");
    }

    static final class NodeLog {
        final int partition, id;
        final Path path;
        final List<SensorRecord> records;
        NodeLog(int partition, int id, Path path, List<SensorRecord> records) {
            this.partition=partition; this.id=id; this.path=path; this.records=records;
        }
        long lastSeq() { return records.isEmpty() ? 0 : records.get(records.size()-1).getSeq(); }
    }

    static List<NodeLog> nodes(int n, Path folder) throws IOException {
        List<NodeLog> result = new ArrayList<>();
        for (Path path : files(folder)) {
            Matcher matcher = NODE.matcher(path.getFileName().toString());
            if (!matcher.matches()) continue;
            int partition=Integer.parseInt(matcher.group(1)), id=Integer.parseInt(matcher.group(2));
            node(partition,id,n);
            List<SensorRecord> records = new ArrayList<>();
            long previous=0;
            for (String line : lines(path)) {
                SensorRecord record=SensorRecord.fromLine(line);
                if(record.getSeq()!=previous+1) throw new IllegalArgumentException(path+": sequências não contíguas");
                previous=record.getSeq();
                records.add(record);
            }
            result.add(new NodeLog(partition,id,path,records));
        }
        return result;
    }

    static final class Event {
        final long time;
        final String type,detail,source;
        final int partition,node,line;
        final Map<String,String> fields=new LinkedHashMap<>();
        Event(long time,String type,int partition,int node,String detail,String source,int line) {
            this.time=time;this.type=type;this.partition=partition;this.node=node;
            this.detail=detail;this.source=source;this.line=line;
            for(String item:detail.split(";")) {
                int equal=item.indexOf('=');
                if(equal>=0) fields.put(item.substring(0,equal),item.substring(equal+1));
                else fields.put(item,"true");
            }
        }
        long number(String name,long missing) {
            String value=fields.get(name);
            return value==null?missing:Long.parseLong(value);
        }
    }

    static List<Event> events(int n,Path folder,boolean monitors) throws IOException {
        List<Event> result=new ArrayList<>();
        for(Path path:files(folder)) {
            String name=path.getFileName().toString();
            Matcher matcher=EVENTS.matcher(name);
            int node=0,expectedPartition=-1;
            if(matcher.matches()) {
                if(monitors)continue;
                expectedPartition=Integer.parseInt(matcher.group(1));node=Integer.parseInt(matcher.group(2));
                node(expectedPartition,node,n);
            } else if(!monitors || !name.startsWith("monitor-") || !name.endsWith(".txt")) continue;
            int lineNumber=0;
            for(String line:lines(path)) {
                lineNumber++;
                String[] fields=line.split(";",4);
                if(fields.length<3 || !fields[2].matches("p\\d+"))
                    throw new IllegalArgumentException(name+":"+lineNumber+": evento inválido");
                int partition=Integer.parseInt(fields[2].substring(1));
                if(partition>=n || (expectedPartition>=0 && partition!=expectedPartition))
                    throw new IllegalArgumentException(name+": partição incompatível");
                result.add(new Event(Long.parseLong(fields[0]),fields[1],partition,node,
                        fields.length==4?fields[3]:"",name,lineNumber));
            }
        }
        return result;
    }

    static final class Heartbeat {
        final int partition,node,count,bytes;
        final long term,hb,time,lastSeq,lastTerm;
        Heartbeat(int partition,int node,String[] f) {
            this.partition=partition;this.node=node;
            term=Long.parseLong(f[0]);hb=Long.parseLong(f[1]);time=Long.parseLong(f[2]);
            lastSeq=Long.parseLong(f[3]);lastTerm=Long.parseLong(f[4]);
            count=Integer.parseInt(f[5]);bytes=Integer.parseInt(f[6]);
            if(term<1||hb<1||lastSeq<0||lastTerm<0||count<0||bytes<0)
                throw new IllegalArgumentException("Heartbeat inválido");
        }
    }

    static List<Heartbeat> heartbeats(int n,Path folder) throws IOException {
        List<Heartbeat> result=new ArrayList<>();
        for(Path path:files(folder)) {
            Matcher matcher=HB.matcher(path.getFileName().toString());
            if(!matcher.matches())continue;
            int partition=Integer.parseInt(matcher.group(1)),node=Integer.parseInt(matcher.group(2));
            node(partition,node,n);
            for(String line:lines(path)) {
                String[] f=line.split(";",-1);
                if(f.length!=7)throw new IllegalArgumentException(path+": formato hb inválido");
                result.add(new Heartbeat(partition,node,f));
            }
        }
        result.sort(Comparator.comparingLong(h->h.time));
        return result;
    }

    static final class Persistent {
        final int partition,node,votedFor;
        final long term;
        Persistent(int partition,int node,String line) {
            this.partition=partition;this.node=node;
            String[] f=line.strip().split(";",-1);
            if(f.length!=2)throw new IllegalArgumentException("Estado persistente incompleto");
            term=Long.parseLong(f[0]);votedFor=Integer.parseInt(f[1]);
            if(term<0||votedFor<0||votedFor>Wire.GRUPO)throw new IllegalArgumentException("Estado persistente inválido");
        }
    }

    static List<Persistent> states(int n,Path folder) throws IOException {
        List<Persistent> result=new ArrayList<>();
        for(Path path:files(folder)) {
            Matcher matcher=STATE.matcher(path.getFileName().toString());
            if(!matcher.matches())continue;
            int partition=Integer.parseInt(matcher.group(1)),node=Integer.parseInt(matcher.group(2));
            node(partition,node,n);
            List<String> content=lines(path);
            if(content.size()!=1)throw new IllegalArgumentException(path+": estado persistente inválido");
            result.add(new Persistent(partition,node,content.get(0)));
        }
        return result;
    }

    static final class Input {
        final long time;
        final int partition,line;
        final String sensor;
        final double temperature;
        Input(String text,int line,int n) {
            String[] f=text.split(";",-1);
            if(f.length!=4||!f[1].matches("p\\d+"))throw new IllegalArgumentException("produtor.txt inválido");
            time=Long.parseLong(f[0]);partition=Integer.parseInt(f[1].substring(1));
            sensor=f[2];temperature=Double.parseDouble(f[3]);this.line=line;
            if(partition>=n||!Double.isFinite(temperature))throw new IllegalArgumentException("Leitura inválida");
        }
        String key() {return partition+";"+sensor+";"+Double.toString(temperature);}
        String text(){return time+";p"+partition+";"+sensor+";"+temperature;}
    }

    static List<Input> inputs(int n,Path folder) throws IOException {
        List<Input> result=new ArrayList<>();
        int line=0;
        for(String text:lines(folder.resolve("produtor.txt")))result.add(new Input(text,++line,n));
        return result;
    }

    static String key(int partition,SensorRecord record) {
        return partition+";"+record.getSensorId()+";"+Double.toString(record.getTemperature());
    }
    static String nodeKey(int partition,int node){return partition+"-"+node;}
}
