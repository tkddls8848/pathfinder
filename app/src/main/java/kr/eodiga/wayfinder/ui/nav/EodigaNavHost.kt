package kr.eodiga.wayfinder.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kr.eodiga.wayfinder.domain.model.Journey
import kr.eodiga.wayfinder.domain.model.Place
import kr.eodiga.wayfinder.ui.screens.GuidanceScreen
import kr.eodiga.wayfinder.ui.screens.HomeScreen
import kr.eodiga.wayfinder.ui.screens.LostScreen
import kr.eodiga.wayfinder.ui.screens.RouteScreen
import kr.eodiga.wayfinder.ui.screens.SearchScreen
import kr.eodiga.wayfinder.ui.screens.SettingsScreen
import javax.inject.Inject
import javax.inject.Singleton

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val ROUTE = "route"
    const val GUIDANCE = "guidance"
    const val LOST = "lost"
    const val SETTINGS = "settings"
}

/**
 * 화면 사이에 오가는 큰 객체(선택한 목적지, 확정한 경로)를 담는 곳.
 *
 * Journey 는 경유 정류소 수백 개를 품을 수 있어 [android.os.Bundle] 로 넘기면
 * TransactionTooLargeException 이 난다. 그래서 라우트 인자로는 넘기지 않고
 * 프로세스 수명 동안 유지되는 이 저장소를 거친다.
 */
@Singleton
class NavigationStore @Inject constructor() {
    var selectedPlace: Place? = null
    var confirmedJourney: Journey? = null

    /**
     * 음성 인식 결과. Activity 가 쓰고 SearchViewModel 이 소비한다.
     * 소비 후 null 로 되돌려, 화면을 다시 열었을 때 지난 검색이 되살아나지 않게 한다.
     */
    val lastVoiceQuery = MutableStateFlow<String?>(null)

    fun consumeVoiceQuery(): String? = lastVoiceQuery.value?.also { lastVoiceQuery.value = null }
}

@Composable
fun EodigaNavHost(
    store: NavigationStore,
    onStartVoiceInput: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onStartJourney: (Journey) -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onDestinationSelected = { place ->
                    store.selectedPlace = place
                    navController.navigate(Routes.ROUTE)
                },
                onSearchOther = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onRequestLocationPermission = onRequestLocationPermission,
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                onPlaceSelected = { place ->
                    store.selectedPlace = place
                    navController.navigate(Routes.ROUTE)
                },
                onStartVoiceInput = onStartVoiceInput,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.ROUTE) {
            val place = store.selectedPlace
            if (place == null) {
                navController.popBackStack(Routes.HOME, inclusive = false)
            } else {
                RouteScreen(
                    destination = place,
                    onStart = { journey ->
                        store.confirmedJourney = journey
                        onStartJourney(journey)
                        navController.navigate(Routes.GUIDANCE) {
                            // 안내 중에 뒤로 눌러 경로 화면으로 돌아가면 혼란스럽다.
                            popUpTo(Routes.HOME) { inclusive = false }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(Routes.GUIDANCE) {
            GuidanceScreen(
                onFinished = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
                onLost = { navController.navigate(Routes.LOST) },
            )
        }

        composable(Routes.LOST) {
            LostScreen(
                onResumeGuidance = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onAddPlace = { navController.navigate(Routes.SEARCH) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
