package dk.foss.jarvis.push

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dk.foss.jarvis.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class CleanupCipher : AeadCipher { override fun encrypt(p:String)="enc($p)"; override fun decrypt(b:String)=b.removePrefix("enc(").removeSuffix(")") }
private class CleanupBlobs : SecretBlobStore { val map=mutableMapOf<String,String>(); override fun get(a:String)=map[a]; override fun put(a:String,b:String){map[a]=b}; override fun remove(a:String){map.remove(a)} }

class FcmRevokeCleanupTest {
 @get:Rule val tmp=TemporaryFolder()
 private fun make(): Triple<PushPrefs,SecureStore,DeviceRegistryStore> { val ds=PreferenceDataStoreFactory.create{File(tmp.newFolder(),"p.preferences_pb")}; val s=SecureStore(CleanupCipher(),CleanupBlobs()); return Triple(PushPrefs(ds),s,DeviceRegistryStore(s)) }
 @Test fun `success with pending clear purges every stored copy of the credential`()=runBlocking { val (p,s,r)=make();r.save("dev","tok","http://a","k");s.saveToken("bearer");p.setPendingRevoke(true);p.setPendingCredentialClear(true);FcmRevokeCleanup.onComplete(r,s,p){};assertNull(s.loadToken());assertNull(s.loadPushApiKey());assertNull(s.loadDeviceId());assertFalse(p.isPendingRevoke());assertFalse(p.isPendingCredentialClear());assertEquals(FcmRegistrationState.DISABLED,p.registrationState.first()) }
 @Test fun `success without pending clear keeps the bearer principal`()=runBlocking { val (p,s,r)=make();r.save("dev","tok","http://a","k");s.saveToken("bearer");p.setPendingRevoke(true);FcmRevokeCleanup.onComplete(r,s,p){};assertEquals("bearer",s.loadToken());assertFalse(p.isPendingRevoke()) }
 @Test fun `completion re-registers when push was re-enabled during the revoke`()=runBlocking { val (p,s,r)=make();r.save("dev","tok","http://a","k");p.enable();p.setPendingRevoke(true);var called=false;FcmRevokeCleanup.onComplete(r,s,p){called=true;p.setRegistrationState(FcmRegistrationState.REGISTERING)};assertTrue(called);assertEquals(FcmRegistrationState.REGISTERING,p.registrationState.first()) }
}
