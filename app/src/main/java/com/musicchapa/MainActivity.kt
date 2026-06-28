package com.musicchapa

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.musicchapa.ui.fragments.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            switchFragment(LibraryFragment())
        }

        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_library -> switchFragment(LibraryFragment())
                R.id.nav_youtube -> switchFragment(YoutubeFragment())
                R.id.nav_soundcloud -> switchFragment(SoundcloudFragment())
                R.id.nav_url -> switchFragment(UrlDownloadFragment())
                R.id.nav_settings -> switchFragment(SettingsFragment())
            }
            true
        }
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}
