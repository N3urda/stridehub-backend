package io.stridehub.app.scale.core;

import java.util.*;

/** Protocol-derived synthetic vectors, not captures from the user's physical scale. */
public final class ProtocolTest {
    private static int checks;
    static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    static void close(double actual,double expected,String label) { check(Math.abs(actual-expected)<0.0001,label+": "+actual); }
    static byte[] bytes(String hex) {
        byte[] data = new byte[hex.length()/2];
        for (int i=0;i<data.length;i++) data[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        return data;
    }
    static byte[] checksum(byte[] data) {
        byte c=0; for(int i=1;i<data.length-1;i++) c^=data[i]; data[data.length-1]=c; return data;
    }
    static byte[] finalFrame(int version) {
        byte[] result=bytes("0d131202006553f1000000002a1b5801f400");
        if(version>=30) { result=Arrays.copyOf(result,20); result[2]=20; result[17]=0x08; result[18]=(byte)0xfc; }
        if(version==35) { result=Arrays.copyOf(result,25); result[2]=25; result[19]=72; result[20]=2; result[21]=0x58; }
        result[1]=(byte)version; return checksum(result);
    }
    static YunmaiProtocol.Frame decode(byte[] data) {
        List<YunmaiProtocol.Frame> values=new YunmaiProtocol.Decoder().accept(data);
        check(values.size()==1,"one measurement parsed"); return values.get(0);
    }
    public static void main(String[] args) throws Exception {
        YunmaiProtocol.Frame old=decode(finalFrame(19));
        check(old.finalMeasurement,"final recognized"); close(old.weightKg,70,"weight");
        check(old.impedance==500 && old.userId==42,"BE impedance and user id");
        check(old.bodyFatPercent==null,"legacy does not interpret checksum as fat");
        check(old.deviceTimestamp==1_700_000_000_000L,"timestamp seconds to millis");
        String event=YunmaiProtocol.replayFingerprint(old,"scaleA");
        check(event.equals(YunmaiProtocol.replayFingerprint(decode(finalFrame(19)),"scaleA")),"replayed final has same identity");
        check(!event.equals(YunmaiProtocol.replayFingerprint(old,"scaleB")),"different scale separate identity");
        byte[] later=finalFrame(19);later[8]++;
        check(!event.equals(YunmaiProtocol.replayFingerprint(decode(checksum(later)),"scaleA")),"same weight later time distinct");
        YunmaiProtocol.Frame unsetClock=decode(finalFrame(19));unsetClock.deviceTimestamp=0;
        check(YunmaiProtocol.replayFingerprint(unsetClock,"scaleA")==null,"unset clock not permanently deduplicated");
        YunmaiProtocol.Frame direct=decode(finalFrame(31)); close(direct.bodyFatPercent,23,"device fat");
        YunmaiProtocol.Frame extended=decode(finalFrame(35)); check(extended.impedance==600,"v35 extended impedance");
        byte[] missing35=finalFrame(35); missing35[20]=0;missing35[21]=0;missing35[22]=1;missing35[23]=(byte)0xf4;
        check(decode(checksum(missing35)).impedance==500,"v35 fallback impedance");
        byte[] bad=finalFrame(19);bad[13]^=1;
        check(new YunmaiProtocol.Decoder().accept(bad).isEmpty(),"bad checksum rejected");
        check(new YunmaiProtocol.Decoder().accept(new byte[]{13,19,2}).isEmpty(),"short length rejected");
        check(new YunmaiProtocol.Decoder().accept(finalFrame(36)).isEmpty(),"unverified version rejected");
        byte[] noWeight=finalFrame(19);noWeight[13]=0;noWeight[14]=0;
        check(new YunmaiProtocol.Decoder().accept(checksum(noWeight)).isEmpty(),"zero weight rejected");
        byte[] zeroResistance=finalFrame(31);zeroResistance[15]=0;zeroResistance[16]=0;
        check(decode(checksum(zeroResistance)).bodyFatPercent==null,"no impedance means no fat");
        byte[] impossibleFat=finalFrame(31);impossibleFat[17]=(byte)0xff;impossibleFat[18]=(byte)0xff;
        check(decode(checksum(impossibleFat)).bodyFatPercent==null,"bad inline fat remains missing");
        for(int cut=1;cut<18;cut++) {
            YunmaiProtocol.Decoder stream=new YunmaiProtocol.Decoder(); byte[] valid=finalFrame(19);
            check(stream.accept(Arrays.copyOfRange(valid,0,cut)).isEmpty(),"fragment buffers "+cut);
            check(stream.accept(Arrays.copyOfRange(valid,cut,18)).size()==1,"fragment completes "+cut);
        }
        byte[] multiple=new byte[39];multiple[0]=0x55;multiple[1]=0x66;multiple[2]=0x77;
        System.arraycopy(finalFrame(19),0,multiple,3,18);System.arraycopy(finalFrame(19),0,multiple,21,18);
        check(new YunmaiProtocol.Decoder().accept(multiple).size()==2,"noise and two frames");
        byte[] response=bytes("0D1F13060105F86204A80120555A16D50103D7");
        YunmaiProtocol.Decoder controlDecoder=new YunmaiProtocol.Decoder();
        check(controlDecoder.accept(response).isEmpty() && controlDecoder.rejectedCount()==0,"APK profile response is valid nonmeasurement");
        Profile profile=new Profile(180,"1990-01-01",true,42);long when=1_700_000_000_000L;
        byte[] user=YunmaiProtocol.userCommand(profile,when);
        check(user.length==18 && user[3]==3 && user[7]==42 && (user[8]&255)==180 && user[9]==1 && user[10]==33,"profile layout");
        check(user[11]==85 && user[12]==90 && user[15]==1 && user[16]==3,"original defaults");
        check(Arrays.equals(checksum(user.clone()),user),"user checksum");
        byte[] time=YunmaiProtocol.timeCommand(when,28800);
        check(time.length==13 && time[7]==20 && time[8]==0 && time[9]==0 && time[10]==0x70 && (time[11]&255)==0x80,"local time layout");
        check(YunmaiProtocol.timeCommand(when,-18000)[8]==(byte)0x80,"negative timezone sign");
        check(Arrays.equals(YunmaiProtocol.versionCommand(),bytes("0d05130016")),"protocol query");
        Measurement estimated=YunmaiProtocol.toMeasurement(old,profile,"device",when);
        close(estimated.bodyFatPercent,18.8,"legacy formula reference");
        check(estimated.source.equals("本机估算"),"estimate marked");
        check(YunmaiProtocol.toMeasurement(direct,profile,"device",when).source.equals("秤内体脂"),"direct marked");
        old.impedance=65535;
        check(YunmaiProtocol.toMeasurement(old,profile,"device",when).bodyFatPercent==null,"invalid sentinel not estimated");
        YunmaiProtocol.Frame femaleFrame=decode(finalFrame(19));
        close(YunmaiProtocol.toMeasurement(femaleFrame,new Profile(180,"1990-01-01",false,42),"device",when).bodyFatPercent,29.6,"female native formula");
        femaleFrame.impedance=150;
        close(YunmaiProtocol.toMeasurement(femaleFrame,profile,"device",when).bodyFatPercent,17.3,"low resistance branch");
        byte[] truncated35=Arrays.copyOf(finalFrame(35),20);truncated35[2]=20;
        check(new YunmaiProtocol.Decoder().accept(checksum(truncated35)).isEmpty(),"v35 truncated payload rejected");
        check(Arrays.equals(YunmaiProtocol.timeQueryCommand(),bytes("0d041713")),"time query");
        check(Arrays.equals(YunmaiProtocol.acknowledgmentCommand(),bytes("0d041410")),"saved ACK");
        String address="11:22:33:44:55:66";
        byte[] adv=bytes("443322110b741704031b5801f400");
        byte xor=0;for(int i=7;i<13;i++)xor^=adv[i];adv[13]=xor;
        YunmaiProtocol.Frame broadcast=YunmaiProtocol.parseAdvertisement(0x5566,adv,address,false);
        check(broadcast!=null && broadcast.finalMeasurement && broadcast.impedance==500,"broadcast parsed");
        close(broadcast.weightKg,70,"broadcast weight");
        for(int r=450;r<=550;r+=50) { broadcast.impedance=r;close(YunmaiProtocol.toMeasurement(broadcast,profile,address,when).bodyFatPercent,16.8+(r-500)/50.0,"broadcast cube-root "+r); }
        check(!YunmaiProtocol.isScaleName("YUNMAI-Massage") && YunmaiProtocol.isScaleName("ALIBABA-M1502"),"exclude other Yunmai products");
        check(YunmaiProtocol.parseAdvertisement(0x5566,adv,"11:22:33:44:55:67",false)==null,"MAC echo checked");
        adv[13]^=1;
        check(YunmaiProtocol.parseAdvertisement(0x5566,adv,address,false)==null,"broadcast checksum checked");
        check(YunmaiProtocol.isScaleName("YUNMAI-ISM") && !YunmaiProtocol.isScaleName("Headphones"),"name filter");
        // mini2S firsthand capture: measurement suffix is unchanged, MAC is synthetic.
        // https://bbs.hassbian.com/thread-14002-1-1.html
        byte[] miniFinal=bytes("4433221102321cde031b2f01cc24");
        YunmaiProtocol.Frame mini=YunmaiProtocol.parseAdvertisement(0x5566,miniFinal,address,true);
        check(mini!=null,"mini2S anonymous barefoot broadcast recognized");
        check(YunmaiProtocol.parseAdvertisement(0x5566,miniFinal,address,false)==null,"mini2S requires service 1320");
        check(mini.finalMeasurement && mini.impedance==460 && mini.session==222,"mini2S final fields");
        close(mini.weightKg,69.59,"mini2S captured weight");
        check(YunmaiProtocol.toMeasurement(mini,profile,address,when).bodyFatPercent!=null,"mini2S local fat estimate");
        byte[] miniLive=bytes("4433221102321cde021b2f0000e8");
        YunmaiProtocol.Frame live=YunmaiProtocol.parseAdvertisement(0x5566,miniLive,address,true);
        check(live!=null && !live.finalMeasurement && live.impedance==0,"mini2S state2 is still live");
        // Reported socks/shoes variant, synthetic measurement/checksum; never invent a final result.
        byte[] socks=bytes("4433221102520cde021b2f0000e8");
        YunmaiProtocol.Frame noFat=YunmaiProtocol.parseAdvertisement(0x5566,socks,address,true);
        check(noFat!=null && !noFat.finalMeasurement && noFat.impedance==0,"mini2S socks broadcast recognized as live");
        check(YunmaiProtocol.parseAdvertisement(0x5567,miniFinal,address,true)==null,"mini2S manufacturer MAC checked");
        byte[] otherMac=miniFinal.clone();otherMac[0]^=1;
        check(YunmaiProtocol.parseAdvertisement(0x5566,otherMac,address,true)==null,"mini2S payload MAC checked");
        byte[] damaged=miniFinal.clone();damaged[13]^=1;
        check(YunmaiProtocol.parseAdvertisement(0x5566,damaged,address,true)==null,"mini2S damaged checksum rejected");
        byte[] unknown=miniFinal.clone();unknown[4]=3;
        check(YunmaiProtocol.parseAdvertisement(0x5566,unknown,address,true)==null,"unknown broadcast family still rejected");
        check(YunmaiProtocol.parseAdvertisement(0x5566,Arrays.copyOf(miniFinal,13),address,true)==null,"truncated mini2S rejected");
        // Product/type metadata from local radio capture; MAC, session, weight and impedance are synthetic.
        byte[] actualVariant=bytes("4433221105521104031b5801f4b1");
        YunmaiProtocol.Frame variant=YunmaiProtocol.parseAdvertisement(0x5566,actualVariant,address,true);
        check(variant!=null && variant.finalMeasurement,"observed mini2S product5 variant accepted");
        close(variant.weightKg,70,"product5 synthetic weight");
        check(variant.impedance==500,"product5 synthetic impedance");
        check(YunmaiProtocol.parseAdvertisement(0x5566,actualVariant,address,false)==null,"product5 requires service1320");
        byte[] metadataChanged=actualVariant.clone();metadataChanged[5]=0x72;metadataChanged[6]=0x20;
        check(YunmaiProtocol.parseAdvertisement(0x5566,metadataChanged,address,true)!=null,"metadata is not a three-byte protocol signature");
        byte[] product5Live=bytes("4433221105521104021b58000045");
        check(!YunmaiProtocol.parseAdvertisement(0x5566,product5Live,address,true).finalMeasurement,"product5 stable weight waits for composition final");
        byte[] product5End=bytes("4433221105521104000000000004");
        check(YunmaiProtocol.parseAdvertisement(0x5566,product5End,address,true)==null,"product5 idle does not create a zero measurement");
        byte[] damagedVariant=actualVariant.clone();damagedVariant[13]^=1;
        check(YunmaiProtocol.parseAdvertisement(0x5566,damagedVariant,address,true)==null,"product5 checksum remains required");
        check(YunmaiProtocol.parseAdvertisement(0x5566,actualVariant,"11:22:33:44:55:67",true)==null,"product5 observed MAC still verified");
        nativeFatPrecisionReferences();
        historicalFatRequiresVerifiedOriginalPacket();
        System.out.println("ProtocolTest: "+checks+" checks passed");
    }
    private static void historicalFatRequiresVerifiedOriginalPacket() {
        long when=1_700_000_000_000L;Profile profile=new Profile(180,"1990-01-01",true,42);
        String address="11:22:33:44:55:66",raw="4433221105521104031b5801f4b1";
        Measurement m=Measurement.create(profile,when,70,null,500,"蓝牙体重",address,raw);
        Double broadcast=YunmaiProtocol.estimateHistoricalFat(m,profile,when);
        check(broadcast!=null,"verified historical broadcast may estimate missing fat");
        close(broadcast,16.8,"historical broadcast uses broadcast formula");
        check(m.bodyFatPercent==null && m.rawPacket.equals(raw),"historical estimate preserves original row fields");
        m.rawPacket=YunmaiProtocol.hex(finalFrame(19));
        close(YunmaiProtocol.estimateHistoricalFat(m,profile,when),18.8,"verified historical GATT uses connection formula");
        m.source="本机估算";
        check(YunmaiProtocol.estimateHistoricalFat(m,profile,when)!=null,"known estimate source accepted");
        m.source="手工录入";check(YunmaiProtocol.estimateHistoricalFat(m,profile,when)==null,"manual source cannot claim a radio protocol");m.source="蓝牙体重";
        m.rawPacket=null;check(YunmaiProtocol.estimateHistoricalFat(m,profile,when)==null,"missing raw never guesses broadcast");
        m.rawPacket="zz";check(YunmaiProtocol.estimateHistoricalFat(m,profile,when)==null,"invalid hex rejected");
        m.rawPacket="0";check(YunmaiProtocol.estimateHistoricalFat(m,profile,when)==null,"odd hex rejected");
        m.rawPacket=raw.substring(0,raw.length()-2)+"00";check(YunmaiProtocol.estimateHistoricalFat(m,profile,when)==null,"bad historical checksum rejected");
        m.rawPacket=raw;m.deviceAddress="11:22:33:45:55:66";check(YunmaiProtocol.estimateHistoricalFat(m,profile,when)==null,"historical broadcast echoed MAC prefix must match");m.deviceAddress=address;
        m.weightKg=70.01;check(YunmaiProtocol.estimateHistoricalFat(m,profile,when)==null,"historical weight must match raw");m.weightKg=70;
        m.impedance=501;check(YunmaiProtocol.estimateHistoricalFat(m,profile,when)==null,"historical impedance must match raw");m.impedance=500;
        m.bodyFatPercent=20.0;check(YunmaiProtocol.estimateHistoricalFat(m,profile,when)==null,"existing original fat is never replaced");close(m.bodyFatPercent,20,"original known fat preserved");m.bodyFatPercent=null;
        check(YunmaiProtocol.estimateHistoricalFat(m,null,when)==null,"historical calculation requires original valid profile");
        check(YunmaiProtocol.estimateHistoricalFat(m,new Profile(180,"2020-01-01",true,42),when)==null,"invalid historical age refused");
        m.rawPacket="4433221105521104021b58000045";m.impedance=0;check(YunmaiProtocol.estimateHistoricalFat(m,profile,when)==null,"live zero-impedance raw cannot estimate");m.impedance=500;
        m.rawPacket=YunmaiProtocol.hex(finalFrame(31));check(YunmaiProtocol.estimateHistoricalFat(m,profile,when)==null,"modern GATT without original fat stays unknown");
        m.rawPacket=YunmaiProtocol.hex(finalFrame(19))+"00";check(YunmaiProtocol.estimateHistoricalFat(m,profile,when)==null,"trailing raw bytes refused");
        m.rawPacket=YunmaiProtocol.hex(finalFrame(19))+YunmaiProtocol.hex(finalFrame(19));check(YunmaiProtocol.estimateHistoricalFat(m,profile,when)==null,"multiple GATT frames refused");
        check(YunmaiProtocol.estimateHistoricalFat(null,profile,when)==null,"missing historical measurement refused");
    }
    private static void nativeFatPrecisionReferences() throws Exception {
        // Synthetic references from the isolated ARM64 JNI oracle, never user captures.
        java.time.LocalDate date=java.time.LocalDate.of(2026,9,29);
        long now=date.atTime(12,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        int compared=0;
        for(String line:java.nio.file.Files.readAllLines(java.nio.file.Paths.get("app/src/test/resources/fat-native-precision.csv"))) {
            if(line.startsWith("id,"))continue;
            String[] row=line.split(",",-1);int sex=Integer.parseInt(row[3]);
            int impedance=Integer.parseInt(row[5]);float nativeFat=Float.parseFloat(row[11]);
            if(!row[8].equals("zh")||!row[7].equals("0")||(sex!=1&&sex!=2)
                    ||impedance<=0||impedance>=65535||nativeFat<5f||nativeFat>75f)continue;
            Profile profile=new Profile(Integer.parseInt(row[1]),date.minusYears(Integer.parseInt(row[2])).toString(),sex==1,42);
            YunmaiProtocol.Frame frame=new YunmaiProtocol.Frame();frame.weightKg=Double.parseDouble(row[4]);frame.impedance=impedance;
            frame.finalMeasurement=true;frame.version=19;frame.broadcast=row[9].equals("brdevices");
            Measurement measurement=YunmaiProtocol.toMeasurement(frame,profile,"synthetic-oracle",now);
            check(measurement.bodyFatPercent!=null,"native fat reference must survive range validation: "+row[0]);
            close(measurement.bodyFatPercent,Math.round(nativeFat*10f)/10.0,"native fat precision reference "+row[0]);
            compared++;
        }
        check(compared==46,"all 46 native precision boundaries compared");
    }
}
