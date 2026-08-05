package kr.eodiga.wayfinder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kr.eodiga.wayfinder.data.local.GuardianEntity
import kr.eodiga.wayfinder.data.remote.ServiceKeyProvider
import kr.eodiga.wayfinder.data.repository.PlaceRepository
import kr.eodiga.wayfinder.domain.model.Place
import kr.eodiga.wayfinder.ui.components.InfoCard
import kr.eodiga.wayfinder.ui.components.PrimaryActionButton
import kr.eodiga.wayfinder.ui.components.ScreenTitle
import kr.eodiga.wayfinder.ui.components.SecondaryActionButton
import kr.eodiga.wayfinder.ui.theme.EodigaColors
import kr.eodiga.wayfinder.ui.theme.EodigaDimens
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val places: PlaceRepository,
    private val keys: ServiceKeyProvider,
) : ViewModel() {

    val guardians: StateFlow<List<GuardianEntity>> = places.guardians()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pinned: StateFlow<List<Place>> = places.pinnedPlaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isConfigured: Boolean get() = keys.hasServiceKey

    fun addGuardian(name: String, phone: String) {
        if (name.isBlank() || phone.isBlank()) return
        viewModelScope.launch {
            places.saveGuardian(
                GuardianEntity(
                    id = UUID.randomUUID().toString(),
                    name = name.trim(),
                    phone = phone.filter { it.isDigit() || it == '+' },
                    priority = guardians.value.size,
                ),
            )
        }
    }

    fun removeGuardian(id: String) {
        viewModelScope.launch { places.deleteGuardian(id) }
    }

    fun removePlace(id: String) {
        viewModelScope.launch { places.deletePlace(id) }
    }
}

/**
 * 설정.
 *
 * 이 화면의 사용자는 어르신이 아니라 **보호자**다. 그래서 여기서만
 * 키보드 입력과 상대적으로 작은 글씨를 허용한다. 다만 어르신이 실수로 들어와도
 * 무언가를 망가뜨리지 않도록, 파괴적인 동작에는 확인 단계를 둔다.
 */
@Composable
fun SettingsScreen(
    onAddPlace: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val guardians by viewModel.guardians.collectAsStateWithLifecycle()
    val pinned by viewModel.pinned.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var confirmingDeleteId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(EodigaDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(EodigaDimens.ElementGap),
    ) {
        item { ScreenTitle("설정") }

        item {
            InfoCard(
                borderColor = if (viewModel.isConfigured) EodigaColors.Success else EodigaColors.Danger,
            ) {
                Text("공공데이터 연결", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (viewModel.isConfigured) {
                        "정상 연결되었습니다."
                    } else {
                        "인증키가 설정되지 않아 버스 정보를 불러올 수 없습니다.\n" +
                            "local.properties 의 PUBLIC_DATA_SERVICE_KEY 를 확인해 주세요."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (viewModel.isConfigured) EodigaColors.Success else EodigaColors.Danger,
                )
            }
        }

        item {
            Text("연락할 가족", style = MaterialTheme.typography.titleLarge)
        }

        items(guardians, key = { it.id }) { guardian ->
            InfoCard {
                Text("${guardian.name} · ${guardian.phone}", style = MaterialTheme.typography.titleLarge)
                if (confirmingDeleteId == guardian.id) {
                    Text("정말 지울까요?", style = MaterialTheme.typography.bodyMedium, color = EodigaColors.Danger)
                    SecondaryActionButton(
                        text = "네, 지웁니다",
                        danger = true,
                        onClick = {
                            viewModel.removeGuardian(guardian.id)
                            confirmingDeleteId = null
                        },
                    )
                    SecondaryActionButton(text = "아니요", onClick = { confirmingDeleteId = null })
                } else {
                    SecondaryActionButton(
                        text = "지우기",
                        danger = true,
                        onClick = { confirmingDeleteId = guardian.id },
                    )
                }
            }
        }

        item {
            InfoCard {
                Text("가족 추가", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("이름 (예: 큰아들)") },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 22.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("전화번호") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    textStyle = TextStyle(fontSize = 22.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryActionButton(
                    text = "추가",
                    onClick = {
                        viewModel.addGuardian(name, phone)
                        name = ""
                        phone = ""
                    },
                    enabled = name.isNotBlank() && phone.isNotBlank(),
                )
            }
        }

        item {
            Text("자주 가는 곳", style = MaterialTheme.typography.titleLarge)
        }

        items(pinned, key = { it.id }) { place ->
            InfoCard {
                Text(place.name, style = MaterialTheme.typography.titleLarge)
                place.address?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = EodigaColors.Muted)
                }
                SecondaryActionButton(
                    text = "지우기",
                    danger = true,
                    onClick = { viewModel.removePlace(place.id) },
                )
            }
        }

        item { PrimaryActionButton(text = "자주 가는 곳 추가", onClick = onAddPlace) }

        item { SecondaryActionButton(text = "뒤로", onClick = onBack) }
    }
}
