package io.stridehub.app;

import java.time.Instant;
import java.time.OffsetDateTime;

/** Explicit protocol metadata: absent expiry alone is never a permanent session. */
public final class SessionLifetime {
    private final boolean persistent;
    private final Instant expiresAt;
    private SessionLifetime(boolean persistent, Instant expiresAt) {
        this.persistent=persistent;this.expiresAt=expiresAt;
    }
    public static SessionLifetime from(boolean persistent,String expiresAt) {
        if(persistent) {
            if(expiresAt!=null)throw new IllegalArgumentException("持久会话不得包含到期时间。");
            return new SessionLifetime(true,null);
        }
        if(expiresAt==null || expiresAt.isEmpty())throw new IllegalArgumentException("限时会话缺少到期时间。");
        try{return new SessionLifetime(false,OffsetDateTime.parse(expiresAt).toInstant());}
        catch(RuntimeException error){throw new IllegalArgumentException("会话到期时间无效。");}
    }
    public boolean persistent(){return persistent;}
    public boolean isUsableAt(Instant now){return persistent || expiresAt.isAfter(now);}
}
