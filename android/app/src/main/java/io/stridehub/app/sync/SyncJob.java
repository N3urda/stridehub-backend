package io.stridehub.app.sync;
import android.app.job.*;
public final class SyncJob extends JobService {
    @Override public boolean onStartJob(JobParameters params){NasSync.runJob(this,retry->jobFinished(params,retry));return true;}
    @Override public boolean onStopJob(JobParameters params){return true;}
}
