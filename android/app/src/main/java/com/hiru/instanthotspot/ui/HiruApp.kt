package com.hiru.instanthotspot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hiru.instanthotspot.MainActivity
import com.hiru.instanthotspot.R
import com.hiru.instanthotspot.ble.BleState
import com.hiru.instanthotspot.ble.LinkStage
import com.hiru.instanthotspot.core.PairingProtocol
import com.hiru.instanthotspot.data.DeviceRole
import com.hiru.instanthotspot.data.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Page { WELCOME, ROLE, CODE, HOME, SEARCH }

@Composable fun HiruApp(activity: MainActivity) {
    val store = activity.app.store
    val engine = activity.app.ble
    val state by engine.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(if (store.onboardingDone && store.key() != null) Page.HOME else Page.WELCOME) }
    var role by rememberSaveable { mutableStateOf(store.role ?: DeviceRole.PROVIDER) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val peers = remember(state.revision) { store.peers() }
    var forget by remember { mutableStateOf<Peer?>(null) }
    var confirmReconfigure by remember { mutableStateOf(false) }
    BackHandler(enabled = page != Page.HOME && page != Page.WELCOME && !saving) {
        engine.stopScan()
        page = when (page) { Page.ROLE -> Page.WELCOME; Page.CODE -> Page.ROLE; else -> Page.HOME }
    }
    HiruTheme {
        CloudBackground {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val notice = saveError ?: state.message
                if (notice != null) {
                    Row(Modifier.fillMaxWidth().background(Pale).padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(notice, Modifier.weight(1f), fontSize = 13.sp, color = Ink, lineHeight = 20.sp)
                        IconButton(onClick = { saveError = null; engine.message(null) }) { Lucide(R.drawable.ic_lucide_x, "안내 닫기", Modifier.size(19.dp)) }
                    }
                }
                when (page) {
                    Page.WELCOME -> Welcome { page = Page.ROLE }
                    Page.ROLE -> OnboardingFrame(onBack = { page = Page.WELCOME }) {
                        RoleChoice { chosen -> role = chosen; page = Page.CODE }
                    }
                    Page.CODE -> OnboardingFrame(onBack = { if (!saving) page = Page.ROLE }) {
                        CodeSetup(role, saving) { code ->
                            saving = true; saveError = null
                            activity.stopProvider(); engine.disconnectClient(); engine.stopScan()
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val secret = PairingProtocol.deriveKey(code)
                                        try { store.saveKey(secret) } finally { secret.fill(0) }
                                        store.role = role; store.onboardingDone = true
                                    }
                                }
                                saving = false
                                if (result.isSuccess) {
                                    engine.refresh(); tab = 0
                                    page = if (role == DeviceRole.PROVIDER) Page.HOME else Page.SEARCH
                                    if (role == DeviceRole.PROVIDER) activity.startProvider(pairing = true) else activity.scan()
                                } else saveError = "등록 코드를 저장하지 못했어요. 다시 시도해 주세요."
                            }
                        }
                    }
                    Page.SEARCH -> OnboardingFrame(onBack = { engine.stopScan(); page = Page.HOME }) {
                        SearchScreen(state, onScan = activity::scan) { address ->
                            page = Page.HOME; tab = 0; activity.request(address)
                        }
                    }
                    Page.HOME -> HomeFrame(tab = tab, onTab = { tab = it }, role = role) {
                        when (tab) {
                            0 -> HomeScreen(role, state, peers,
                                start = { activity.startProvider() }, stop = activity::stopProvider,
                                pair = { activity.startProvider(pairing = true) },
                                search = { page = Page.SEARCH; activity.scan() },
                                hotspot = activity::openHotspotSettings, wifi = activity::openWifiSettings,
                                dismissRequest = engine::clearRequest, disconnect = engine::disconnectClient,
                                wifiInternet = activity.wifiInternet.value)
                            1 -> DevicesScreen(peers, role, onAdd = {
                                if (role == DeviceRole.PROVIDER) activity.startProvider(pairing = true)
                                else { page = Page.SEARCH; activity.scan() }
                            }, onForget = { forget = it }, pairing = state.pairingUntil > 0)
                            2 -> SettingsScreen(role, onReconfigure = { confirmReconfigure = true },
                                openSettings = if (role == DeviceRole.PROVIDER) activity::openHotspotSettings else activity::openWifiSettings)
                        }
                    }
                }
            }
        }
        forget?.let { peer ->
            AlertDialog(onDismissRequest = { forget = null }, title = { Text("기기 등록 해제") },
                text = { Text("${peer.name}의 등록을 이 기기에서 해제할까요? 다시 연결하려면 등록을 진행해야 해요.") },
                confirmButton = { TextButton(onClick = { engine.forgetPeer(peer.id); forget = null }) { Text("등록 해제") } },
                dismissButton = { TextButton(onClick = { forget = null }) { Text("취소") } })
        }
        if (confirmReconfigure) AlertDialog(onDismissRequest = { confirmReconfigure = false },
            title = { Text("역할·등록 코드 다시 설정") }, text = { Text("진행하면 대기를 중지해요. 새 코드를 저장하면 기존 기기 등록이 해제돼요.") },
            confirmButton = { TextButton(onClick = {
                confirmReconfigure = false; activity.stopProvider(); engine.stopScan(); engine.disconnectClient(); page = Page.ROLE
            }) { Text("다시 설정") } }, dismissButton = { TextButton(onClick = { confirmReconfigure = false }) { Text("취소") } })
    }
}

@Composable private fun Welcome(onStart: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(28.dp))
            Halo(R.drawable.ic_lucide_wifi, large = true)
            Spacer(Modifier.height(10.dp))
            Text("Hiru", fontSize = 46.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("Instant Hotspot", fontSize = 21.sp, color = Ink)
            Spacer(Modifier.height(10.dp))
            Text("가까운 기기의 인터넷을\n빠르고 간편하게 연결하세요", textAlign = TextAlign.Center, color = Muted, fontSize = 15.sp, lineHeight = 25.sp)
            Spacer(Modifier.height(46.dp))
            SkyButton("시작하기", onStart, Modifier.fillMaxWidth(), icon = R.drawable.ic_lucide_arrow_right)
            Text("폰과 태블릿, 하나의 앱으로", fontSize = 12.sp, color = Muted)
        }
    }
}
@Composable private fun OnboardingFrame(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp, top = 8.dp)) { Lucide(R.drawable.ic_lucide_arrow_left, "뒤로") }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 540.dp).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
        }
    }
}
@Composable private fun RoleChoice(onChoose: (DeviceRole) -> Unit) {
    PageTitle("기기 역할 선택", "이 기기를 어떻게 사용할까요?")
    Spacer(Modifier.height(8.dp))
    SoftCard(Modifier.fillMaxWidth().clickable { onChoose(DeviceRole.PROVIDER) }) {
        DeviceRow("인터넷 제공", "폰의 인터넷을 다른 기기와 공유해요", onClick = { onChoose(DeviceRole.PROVIDER) })
    }
    SoftCard(Modifier.fillMaxWidth().clickable { onChoose(DeviceRole.CLIENT) }) {
        DeviceRow("인터넷 연결", "폰에 연결해 인터넷을 사용해요", R.drawable.ic_lucide_tablet, onClick = { onChoose(DeviceRole.CLIENT) })
    }
    QuietNote("기기 종류에 관계없이 역할을 선택할 수 있어요.", icon = R.drawable.ic_lucide_circle_help)
}
@Composable private fun CodeSetup(role: DeviceRole, saving: Boolean, onSave: (String) -> Unit) {
    // The code is deliberately not saveable: it is not copied into saved-instance state.
    var code by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    PageTitle(if (role == DeviceRole.PROVIDER) "등록 코드 설정" else "등록 코드 입력", "두 기기에 같은 10자리 코드를 입력하세요.")
    OutlinedTextField(value = code, onValueChange = { value -> if (value.length <= 10 && value.all { it.code in 33..126 }) code = value },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving,
        label = { Text("등록 코드") }, placeholder = { Text("영문·숫자·특수기호 10자리") },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), shape = RoundedCornerShape(20.dp),
        trailingIcon = { IconButton(onClick = { visible = !visible }) { Lucide(if (visible) R.drawable.ic_lucide_eye_off else R.drawable.ic_lucide_eye, if (visible) "코드 숨기기" else "코드 보기") } })
    SoftCard {
        Requirement("정확히 10자리", code.length == 10)
        Requirement("영어 대문자 · 소문자", code.any { it in 'A'..'Z' } && code.any { it in 'a'..'z' })
        Requirement("숫자 · 특수기호", code.any { it in '0'..'9' } && code.any { !it.isLetterOrDigit() })
        HorizontalDivider(color = Line)
        QuietNote("등록할 때 한 번만 입력하면 돼요.\n두 기기에서 같은 코드를 사용해 주세요.")
    }
    if (saving) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("안전하게 등록 정보를 준비하고 있어요", fontSize = 13.sp, color = Muted)
    }
    SkyButton(if (saving) "준비 중" else "다음", { onSave(code); code = "" }, Modifier.fillMaxWidth(), enabled = !saving && PairingProtocol.validCode(code))
}
@Composable private fun Requirement(text: String, met: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).background(if (met) Sky else Line, CircleShape), contentAlignment = Alignment.Center) {
            Lucide(R.drawable.ic_lucide_check, modifier = Modifier.size(14.dp), tint = if (met) Color.White else Muted)
        }
        Text(text, fontSize = 14.sp, color = if (met) Ink else Muted)
    }
}

@Composable private fun SearchScreen(state: BleState, onScan: () -> Unit, onSelect: (String) -> Unit) {
    PageTitle(if (state.scanning) "주변 기기 검색 중" else "주변 기기", "폰에서 요청 대기와 기기 등록을 시작해 주세요.")
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Halo(R.drawable.ic_lucide_bluetooth) }
    if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Sky, trackColor = Pale)
    SoftCard(Modifier.fillMaxWidth()) {
        if (state.found.isEmpty()) {
            Text(if (state.scanning) "가까운 폰을 찾고 있어요" else "검색된 기기가 없어요", fontWeight = FontWeight.Medium)
            Text("두 기기의 Bluetooth를 켜고 가까이 두세요. 폰에서 '기기 등록'을 누르면 새 기기를 연결할 수 있어요.", fontSize = 13.sp, color = Muted, lineHeight = 21.sp)
        }
        state.found.forEachIndexed { i, device ->
            if (i > 0) HorizontalDivider(color = Line)
            DeviceRow(device.name, "연결 가능 · 눌러서 인증하고 요청", onClick = { onSelect(device.address) })
        }
    }
    SkyButton(if (state.scanning) "검색 중" else "다시 검색", onScan, Modifier.fillMaxWidth(), enabled = !state.scanning, icon = R.drawable.ic_lucide_refresh_cw)
    QuietNote("검색은 최대 10초 동안 진행돼요.", icon = R.drawable.ic_lucide_circle_help)
}

@Composable private fun HomeFrame(tab: Int, onTab: (Int) -> Unit, role: DeviceRole, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        if (wide) Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = Color.White.copy(alpha = .65f), modifier = Modifier.width(100.dp), header = {
                Spacer(Modifier.height(12.dp)); Lucide(R.drawable.ic_lucide_wifi, modifier = Modifier.size(32.dp), tint = Sky)
                Text("Hiru", fontWeight = FontWeight.SemiBold, color = Ink); Spacer(Modifier.height(32.dp))
            }) { RailNav(tab, onTab) }
            Box(Modifier.weight(1f).fillMaxHeight()) { ScrollContent(content) }
        } else Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { ScrollContent(content) }
            NavigationBar(containerColor = Color.White.copy(alpha = .75f), tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) { BottomNav(tab, onTab) }
        }
    }
}
@Composable private fun RailNav(tab: Int, onTab: (Int) -> Unit) {
    val names = listOf("홈", "기기", "설정")
    val icons = listOf(R.drawable.ic_lucide_house, R.drawable.ic_lucide_smartphone, R.drawable.ic_lucide_settings)
    names.forEachIndexed { index, label ->
        NavigationRailItem(selected = index == tab, onClick = { onTab(index) },
            icon = { Lucide(icons[index], tint = if (index == tab) Sky else Muted) }, label = { Text(label) },
            colors = NavigationRailItemDefaults.colors(indicatorColor = Pale))
    }
}
@Composable private fun RowScope.BottomNav(tab: Int, onTab: (Int) -> Unit) {
    val names = listOf("홈", "기기", "설정")
    val icons = listOf(R.drawable.ic_lucide_house, R.drawable.ic_lucide_smartphone, R.drawable.ic_lucide_settings)
    names.forEachIndexed { index, label ->
        NavigationBarItem(selected = index == tab, onClick = { onTab(index) },
            icon = { Lucide(icons[index], tint = if (index == tab) Sky else Muted) }, label = { Text(label) },
            colors = NavigationBarItemDefaults.colors(indicatorColor = Pale))
    }
}
@Composable private fun ScrollContent(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 1100.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) { content() }
    }
}
@Composable private fun SplitCards(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 650.dp) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) { first() }; Column(Modifier.weight(1f)) { second() }
        } else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) { first(); second() }
    }
}
@Composable private fun HomeScreen(role: DeviceRole, state: BleState, peers: List<Peer>, start: () -> Unit, stop: () -> Unit,
    pair: () -> Unit, search: () -> Unit, hotspot: () -> Unit, wifi: () -> Unit, dismissRequest: () -> Unit,
    disconnect: () -> Unit, wifiInternet: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        PageTitle(if (role == DeviceRole.PROVIDER) "인터넷 제공" else "인터넷 연결", "Hiru Instant Hotspot")
        if (state.advertising) Badge(containerColor = Pale, contentColor = Color(0xFF4B84C6)) { Text("대기 중", Modifier.padding(7.dp)) }
    }
    if (role == DeviceRole.PROVIDER) {
        state.incomingRequest?.let { name ->
            SoftCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Lucide(R.drawable.ic_lucide_bluetooth, tint = Sky); Text("연결 요청이 왔어요", fontWeight = FontWeight.Bold)
                }
                Text("$name\n핫스팟을 켜면 상대 기기가 Wi-Fi로 연결할 수 있어요.", color = Muted, fontSize = 14.sp, lineHeight = 23.sp)
                SkyButton("핫스팟 설정 열기", hotspot, Modifier.fillMaxWidth())
                TextButton(onClick = dismissRequest) { Text("요청 닫기") }
            }
        }
        SplitCards(first = {
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Halo(R.drawable.ic_lucide_radio)
                    Text(if (state.advertising) "요청을 기다리고 있어요" else "연결할 준비를 해볼까요?", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(if (state.advertising) "등록된 기기에서 연결을 요청할 수 있어요" else "대기를 시작하면 태블릿에서 폰을 찾을 수 있어요", fontSize = 13.sp, color = Muted, lineHeight = 21.sp, textAlign = TextAlign.Center)
                    SkyButton(if (state.advertising) "대기 중지" else "대기 시작", if (state.advertising) stop else start, Modifier.fillMaxWidth())
                }
            }
        }, second = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SoftCard(Modifier.fillMaxWidth()) {
                    Text("등록된 기기", fontWeight = FontWeight.SemiBold)
                    if (peers.isEmpty()) Text("아직 등록된 기기가 없어요", color = Muted, fontSize = 14.sp)
                    peers.take(3).forEach { DeviceRow(it.name, "등록됨", R.drawable.ic_lucide_tablet) }
                    OutlinedButton(onClick = pair, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Lucide(R.drawable.ic_lucide_plus, tint = Sky); Spacer(Modifier.width(8.dp)); Text(if (state.pairingUntil > 0) "기기 등록 열림 · 2분" else "기기 등록")
                    }
                }
                QuietNote("요청을 받으면 폰에서 핫스팟을 켜 주세요.\n대기 알림을 누르면 이 화면으로 돌아올 수 있어요.", icon = R.drawable.ic_lucide_circle_help)
            }
        })
    } else {
        val busy = state.stage in listOf(LinkStage.CONNECTING, LinkStage.AUTHENTICATING, LinkStage.REQUESTING)
        val waiting = state.stage == LinkStage.ACTION_REQUIRED
        SplitCards(first = {
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Halo(if (waiting) R.drawable.ic_lucide_check else R.drawable.ic_lucide_bluetooth)
                    Text(when (state.stage) {
                        LinkStage.CONNECTING -> "폰에 연결 중이에요"
                        LinkStage.AUTHENTICATING -> "등록 정보를 확인하고 있어요"
                        LinkStage.REQUESTING -> "핫스팟을 요청하고 있어요"
                        LinkStage.ACTION_REQUIRED -> "폰에 요청을 보냈어요"
                        else -> "가까운 폰과 연결해요"
                    }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(if (waiting) "폰에서 핫스팟을 켠 후\n태블릿의 Wi-Fi 설정에서 연결해 주세요." else "같은 등록 코드를 사용하는 폰에\n안전하게 연결 요청을 보내요.", color = Muted, fontSize = 14.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Sky, trackColor = Pale)
                        TextButton(onClick = disconnect) { Text("취소") }
                    } else SkyButton(if (waiting) "Wi-Fi 설정 열기" else "폰 찾기", if (waiting) wifi else search, Modifier.fillMaxWidth())
                    if (waiting) TextButton(onClick = disconnect) { Text("다른 요청 보내기") }
                }
            }
        }, second = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SoftCard(Modifier.fillMaxWidth()) {
                    Text("내 기기", fontWeight = FontWeight.SemiBold)
                    if (peers.isEmpty()) Text("폰을 찾아 처음 연결해 보세요", color = Muted, fontSize = 14.sp)
                    peers.forEach { DeviceRow(it.name, "등록됨 · 눌러서 주변에서 찾기", onClick = if (!busy) search else null) }
                    if (state.peerName.isNotEmpty() && busy) DeviceRow(state.peerName, "연결 진행 중")
                }
                QuietNote(if (wifiInternet) "Wi-Fi 인터넷 연결이 확인됐어요.\n요청한 폰의 네트워크인지는 Wi-Fi 설정에서 확인해 주세요." else "등록된 기기만 연결을 요청할 수 있어요.", icon = if (wifiInternet) R.drawable.ic_lucide_wifi else R.drawable.ic_lucide_lock_keyhole)
            }
        })
    }
}

@Composable private fun DevicesScreen(peers: List<Peer>, role: DeviceRole, onAdd: () -> Unit, onForget: (Peer) -> Unit, pairing: Boolean) {
    PageTitle("내 기기", "처음 등록한 기기는 다음에도 같은 코드로 인증해요.")
    SoftCard(Modifier.fillMaxWidth()) {
        if (peers.isEmpty()) {
            Halo(R.drawable.ic_lucide_smartphone)
            Text("등록된 기기가 없어요", fontWeight = FontWeight.Medium)
        }
        peers.forEachIndexed { i, peer ->
            if (i > 0) HorizontalDivider(color = Line)
            DeviceRow(peer.name, "등록됨", if (role == DeviceRole.PROVIDER) R.drawable.ic_lucide_tablet else R.drawable.ic_lucide_smartphone,
                trailing = { IconButton(onClick = { onForget(peer) }) { Lucide(R.drawable.ic_lucide_trash_2, "${peer.name} 등록 해제", tint = Muted) } })
        }
        SkyButton(if (pairing && role == DeviceRole.PROVIDER) "기기 등록 열림 · 다시 2분" else "기기 등록", onAdd, Modifier.fillMaxWidth(), icon = R.drawable.ic_lucide_plus)
    }
    if (role == DeviceRole.PROVIDER) QuietNote("기기 등록을 누른 후 2분 안에 상대 기기에서 같은 코드를 입력하고 폰을 찾아 주세요.", icon = R.drawable.ic_lucide_circle_help)
}
@Composable private fun SettingsScreen(role: DeviceRole, onReconfigure: () -> Unit, openSettings: () -> Unit) {
    PageTitle("설정", "간편하게 연결하고, 필요한 때만 사용해요.")
    SoftCard(Modifier.fillMaxWidth()) {
        DeviceRow("현재 역할", if (role == DeviceRole.PROVIDER) "인터넷 제공" else "인터넷 연결", if (role == DeviceRole.PROVIDER) R.drawable.ic_lucide_smartphone else R.drawable.ic_lucide_tablet)
        HorizontalDivider(color = Line)
        DeviceRow("역할·등록 코드 다시 설정", "기존 기기 등록도 함께 초기화해요", R.drawable.ic_lucide_lock_keyhole, onClick = onReconfigure)
        HorizontalDivider(color = Line)
        DeviceRow(if (role == DeviceRole.PROVIDER) "핫스팟 설정" else "Wi-Fi 설정", "기기의 시스템 설정 열기", R.drawable.ic_lucide_wifi, onClick = openSettings)
    }
    SoftCard(Modifier.fillMaxWidth()) {
        Text("배터리를 위한 연결", fontWeight = FontWeight.SemiBold)
        Text("폰은 저전력 BLE 광고로 대기해요. 기기 검색은 최대 10초이며, 앱 화면을 벗어나면 중지해요. 요청을 보내고 응답을 받으면 BLE 연결을 종료해요.", color = Muted, fontSize = 14.sp, lineHeight = 23.sp)
        HorizontalDivider(color = Line)
        Text("현재 버전 안내", fontWeight = FontWeight.SemiBold)
        Text("이 버전은 BLE 등록·인증·핫스팟 요청을 지원해요. 폰의 핫스팟 켜기와 태블릿의 Wi-Fi 연결은 시스템 설정에서 진행해 주세요.", color = Muted, fontSize = 14.sp, lineHeight = 23.sp)
    }
    Text("Hiru Instant Hotspot · 0.1.0\nIcons by Lucide · ISC License", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable private fun WelcomePreview() { HiruTheme { CloudBackground { Welcome {} } } }
@Preview(showBackground = true, widthDp = 1000, heightDp = 700)
@Composable private fun TabletPreview() {
    HiruTheme { CloudBackground { ScrollContent {
        HomeScreen(DeviceRole.PROVIDER, BleState(advertising = true), emptyList(), {}, {}, {}, {}, {}, {}, {}, {}, false)
    } } }
}
