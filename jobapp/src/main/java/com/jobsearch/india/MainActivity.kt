package com.jobsearch.india

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.jobsearch.india.ui.GalleryApp
import com.jobsearch.india.ui.theme.IndianJobsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IndianJobsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GalleryApp()
                }
            }
        }
    }
}
