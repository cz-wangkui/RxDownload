package zlc.season.rxdownload.kotlin_demo

import android.app.Application
import io.reactivex.exceptions.UndeliverableException
import io.reactivex.plugins.RxJavaPlugins
import zlc.season.rxdownload4.utils.log


class BaseApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        RxJavaPlugins.setErrorHandler {
            if (it is UndeliverableException) {
                //do nothing
                "do nothing".log()
            } else {
                it.log()
            }
        }
    }
}