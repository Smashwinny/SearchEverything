package com.search.everything

import android.app.Application
import com.github.promeg.pinyinhelper.Pinyin
import com.github.promeg.tinypinyin.lexicons.android.cncity.CnCityDict

class SearchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Pinyin.init(Pinyin.newConfig().with(CnCityDict.getInstance(this)))
    }
}
