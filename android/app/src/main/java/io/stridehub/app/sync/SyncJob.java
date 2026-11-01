package io.stridehub.app.sync;
import android.app.job.*;
import android.os.Handler;
import android.os.Looper;
public final class SyncJob extends JobService {
    private Invocation current;
    private static final class Invocation {
        final JobParameters parameters;
        final long triggerGeneration=NasSync.triggerGeneration();
        volatile boolean stopped;
        Invocation(JobParameters parameters){this.parameters=parameters;}
    }
    @Override public boolean onStartJob(JobParameters parameters){
        if(current!=null)current.stopped=true;
        Invocation invocation=new Invocation(parameters);current=invocation;
        NasSync.runJob(getApplicationContext(),()->!invocation.stopped,retry->new Handler(Looper.getMainLooper()).post(()->{
            // An old stopped worker must never finish a newer invocation of this job ID.
            if(current!=invocation||invocation.stopped)return;
            current=null;jobFinished(parameters,retry);
            // A foreground attempt may have failed while this completed job still occupied JOB_ID.
            // Preserve its fallback after finishing, without postponing existing retry jobs.
            if(!retry&&invocation.triggerGeneration!=NasSync.triggerGeneration())NasSync.schedule(getApplicationContext());
        }));
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters){
        if(current!=null&&current.parameters==parameters){current.stopped=true;current=null;}
        return true;
    }
    @Override public void onDestroy(){
        if(current!=null){current.stopped=true;current=null;}
        super.onDestroy();
    }
}
