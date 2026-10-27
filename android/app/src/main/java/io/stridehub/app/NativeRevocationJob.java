package io.stridehub.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** JobScheduler persists network-required logout retries across process death and reboot. */
public final class NativeRevocationJob extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean stopped;
    @Override public boolean onStartJob(JobParameters parameters) {
        stopped=false;
        worker.execute(()->{
            boolean complete=false;
            try{complete=NativeRevocations.drain(getApplicationContext());}
            finally{if(!stopped)jobFinished(parameters,!complete);}
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters){stopped=true;return true;}
    @Override public void onDestroy(){stopped=true;worker.shutdownNow();super.onDestroy();}
}
