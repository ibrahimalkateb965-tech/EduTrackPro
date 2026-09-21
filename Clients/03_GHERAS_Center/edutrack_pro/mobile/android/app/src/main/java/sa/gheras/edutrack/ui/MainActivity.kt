package sa.gheras.edutrack.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import sa.gheras.edutrack.GherasApp
import sa.gheras.edutrack.ui.nav.RootNavHost
import sa.gheras.edutrack.ui.theme.GherasTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as GherasApp
        val container = app.container

        setContent {
            GherasTheme {
                val navController = rememberNavController()
                RootNavHost(
                    container = container,
                    navController = navController
                )
            }
        }
    }
}
