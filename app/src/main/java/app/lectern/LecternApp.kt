package app.lectern

import android.app.Application
import app.lectern.data.Db
import app.lectern.data.Prefs
import app.lectern.play.Playback

class LecternApp : Application() {
    lateinit var library: Library
        private set
    lateinit var pcSync: app.lectern.sync.PcSync
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val prefs = Prefs(this)
        library = Library(this, Db(this), prefs)
        pcSync = app.lectern.sync.PcSync(this, library)
        Playback.init(this, library)
    }

    companion object {
        lateinit var instance: LecternApp
            private set
    }
}
