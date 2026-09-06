package dk.foss.jarvis.push

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dk.foss.jarvis.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class TestCipher : AeadCipher { override fun encrypt(p:String)="enc($p)"; override fun decrypt(b:String)=b.removePrefix("enc(").removeSuffix(")") }
private class TestBlobs : SecretBlobStore { val map=mutableMapOf<String,String>(); override fun get(a:String)=map[a]; override fun put(a:String,b:String){map[a]=b}; override fun remove(a:String){map.remove(a)} }

class FcmConnectionEffectsTest {
    @get:Rule val tmp=TemporaryFolder()
    private fun make(): Quad { val ds=PreferenceDataStoreFactory.create{File(tmp.newFolder(),"push.preferences_pb")}; val b=TestBlobs(); return Quad(PushPrefs(ds),SecureStore(TestCipher(),b),DeviceRegistryStore(SecureStore(TestCipher(),b)),mutableListOf(),mutableListOf()) }
    private data class Quad(val p:PushPrefs,val s:SecureStore,val r:DeviceRegistryStore,val a:MutableList<Int>,val c:MutableList<Int>)
    @Test fun `clear without registration purges residual records and flags`()=runBlocking { val q=make(); q.s.savePushApiKey("stale");q.p.setPendingCredentialClear(true); FcmConnectionEffects(q.p,q.r,{q.a+=1},{q.c+=1}).onConnectionChanged(JarvisSettings("http://a","k-old"),JarvisSettings("http://a","")); assertEquals(RegistryState.Empty,q.r.loadOrMigrate(JarvisSettings("http://a","k-old"))); assertFalse(q.p.isPendingCredentialClear());assertFalse(q.p.isPendingRevoke());assertFalse(q.p.isEnabled());assertEquals(FcmRegistrationState.DISABLED,q.p.registrationState.first());assertTrue(q.a.isEmpty());assertTrue(q.c.isEmpty()) }
    @Test fun `clear with registration revokes before purging`()=runBlocking { val q=make();q.r.save("dev","tok","http://a","k-old");q.p.enable(); FcmConnectionEffects(q.p,q.r,{q.a+=1},{q.c+=1}).onConnectionChanged(JarvisSettings("http://a","k-old"),JarvisSettings("http://a",""));assertFalse(q.p.isEnabled());assertTrue(q.p.isPendingRevoke());assertTrue(q.p.isPendingCredentialClear());assertEquals(FcmRegistrationState.UNREGISTERING,q.p.registrationState.first());assertEquals(1,q.a.size);assertEquals(1,q.c.size);assertEquals("k-old",q.s.loadPushApiKey()) }
    @Test fun `clear after legacy migration revokes the bound device`()=runBlocking { val q=make();q.s.saveDeviceId("legacy-device");q.s.savePushEndpoint("tok");FcmConnectionEffects(q.p,q.r,{q.a+=1},{q.c+=1}).onConnectionChanged(JarvisSettings("http://a","k-old"),JarvisSettings("http://a",""));assertTrue(q.p.isPendingRevoke());assertEquals(1,q.a.size);assertTrue(q.r.loadOrMigrate(JarvisSettings("http://a","k-old")) is RegistryState.Registered) }
    @Test fun `unbindable legacy record is purged on clear instead of blocking forever`()=runBlocking { val q=make();q.s.saveDeviceId("legacy");q.s.savePushEndpoint("tok");FcmConnectionEffects(q.p,q.r,{q.a+=1},{q.c+=1}).onConnectionChanged(JarvisSettings("","k-old"),JarvisSettings("",""));assertEquals(RegistryState.Empty,q.r.loadOrMigrate(JarvisSettings("","k-old")));assertTrue(q.a.isEmpty());assertFalse(q.p.isPendingRevoke()) }
    @Test fun `saving a new bearer supersedes a pending clear`()=runBlocking { val q=make();q.r.save("dev","tok","http://a","k-old");q.p.setPendingRevoke(true);q.p.setPendingCredentialClear(true);FcmConnectionEffects(q.p,q.r,{q.a+=1},{q.c+=1}).onConnectionChanged(JarvisSettings("http://a",""),JarvisSettings("http://b","k-b"));assertFalse(q.p.isPendingCredentialClear());assertTrue(q.p.isPendingRevoke());assertEquals(1,q.a.size);assertEquals("k-old",q.s.loadPushApiKey()) }
    @Test fun `A to B revokes against A and keeps pinned credentials`()=runBlocking { val q=make();q.r.save("dev","tok","http://a","k-old");q.p.enable();FcmConnectionEffects(q.p,q.r,{q.a+=1},{q.c+=1}).onConnectionChanged(JarvisSettings("http://a","k-old"),JarvisSettings("http://b","k-new"));assertTrue(q.p.isPendingRevoke());assertEquals("k-old",q.s.loadPushApiKey());assertEquals(1,q.a.size) }
    @Test fun `bearer-only change refreshes credential but not origin`()=runBlocking { val q=make();q.r.save("dev","tok","http://a","k-old");FcmConnectionEffects(q.p,q.r,{q.a+=1},{q.c+=1}).onConnectionChanged(JarvisSettings("http://a","k-old"),JarvisSettings("http://a","k-new"));assertEquals("http://a",q.s.loadPushOrigin());assertEquals("k-new",q.s.loadPushApiKey());assertTrue(q.a.isEmpty()) }
    @Test fun `bearer-only change is skipped while a revoke is pending`()=runBlocking { val q=make();q.r.save("dev","tok","http://a","k-old");q.p.setPendingRevoke(true);FcmConnectionEffects(q.p,q.r,{q.a+=1},{q.c+=1}).onConnectionChanged(JarvisSettings("http://a","k-old"),JarvisSettings("http://a","k-new"));assertEquals("k-old",q.s.loadPushApiKey());assertTrue(q.a.isEmpty()) }
}
