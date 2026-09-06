package dk.foss.jarvis.push
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
class PushPrefsStateTest { @get:Rule val tmp=TemporaryFolder()
 @Test fun `pending credential clear persists across process recreation`()=runBlocking { val ds=PreferenceDataStoreFactory.create{File(tmp.newFolder(),"p.preferences_pb")}; val p=PushPrefs(ds);p.setPendingCredentialClear(true);assertEquals(true,ds.data.first()[PushPrefs.Keys.PENDING_CREDENTIAL_CLEAR]);assertEquals(true,p.isPendingCredentialClear()) }
 @Test fun `pending flags default to false`()=runBlocking { val ds=PreferenceDataStoreFactory.create{File(tmp.newFolder(),"p2.preferences_pb")};val p=PushPrefs(ds);assertEquals(false,p.isPendingRevoke());assertEquals(false,p.isPendingCredentialClear());assertEquals(false,p.isEnabled()) }
}
