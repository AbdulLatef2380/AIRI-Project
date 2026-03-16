package com.airi.assistant.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ai.ModelManager
import com.airi.assistant.ai.ModelRegistry
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AiriApp(onImportModel: () -> Unit, onStartAiri: () -> Unit) {
    val scaffoldState = rememberScaffoldState()
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showModelManager by remember { mutableStateOf(false) }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Scaffold(
            scaffoldState = scaffoldState,
            backgroundColor = Color.Transparent,
            drawerContent = { 
                SidebarContent(
                    onClose = { scope.launch { scaffoldState.drawerState.close() } }, 
                    onOpenSettings = { showSettings = true },
                    onOpenModels = { showModelManager = true }
                ) 
            },
            drawerBackgroundColor = AiriDarkBg,
            drawerShape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp),
            topBar = {
                TopAppBar(
                    backgroundColor = Color.Transparent,
                    elevation = 0.dp,
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { scaffoldState.drawerState.open() } }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = Color.White)
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                // الشاشة الرئيسية (الحالة الفارغة أو الدردشة)
                EmptyState(onImportModel)

                // شريط الإدخال في الأسفل
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    InputBar(onStartAiri)
                }

                // شاشة الإعدادات كـ Overlay
                if (showSettings) {
                    SettingsScreen(onClose = { showSettings = false })
                }
                
                // شاشة إدارة النماذج
                if (showModelManager) {
                    ModelManagerScreen(onClose = { showModelManager = false }, onImport = onImportModel)
                }
            }
        }
    }
}

@Composable
fun SidebarContent(onClose: () -> Unit, onOpenSettings: () -> Unit, onOpenModels: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.ArrowForward, contentDescription = "Back", tint = Color.White)
            }
            Row(
                modifier = Modifier.background(Color(0xFF222222), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Rounded.Person, "", tint = AiriCyan, modifier = Modifier.size(20.dp).clickable { onOpenSettings() })
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.Search, "", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(text = "Airi assistant ai", color = AiriCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Divider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 15.dp))

        NavItem(Icons.Rounded.Layers, "النماذج", onClick = onOpenModels)
        NavItem(Icons.Rounded.GridView, "التطبيقات")

        Spacer(Modifier.height(30.dp))
        Text("دردشاتك", color = Color(0xFF444444), fontSize = 12.sp)
        Text("سجل الدردشة فارغ", color = Color(0xFF222222), fontSize = 14.sp, modifier = Modifier.padding(vertical = 20.dp).align(Alignment.CenterHorizontally))

        Spacer(Modifier.weight(1f))
        NavItem(Icons.Rounded.ChatBubbleOutline, "Chat", textColor = AiriCyan)
    }
}

@Composable
fun InputBar(onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
            .background(AiriPanelBg, RoundedCornerShape(30.dp))
            .border(1.dp, Color(0xFF222222), RoundedCornerShape(30.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(AiriPink, CircleShape).clickable { onSend() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.GraphicEq, "", tint = Color.White, modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.Mic, "", tint = Color(0xFF777777))

        TextField(
            value = "",
            onValueChange = {},
            placeholder = { Text("أطرح سؤالك على AIRI...", color = Color(0xFF555555), fontSize = 14.sp) },
            modifier = Modifier.weight(1f),
            colors = TextFieldDefaults.textFieldColors(
                backgroundColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                textColor = Color.White
            )
        )

        Icon(Icons.Rounded.Add, "", tint = AiriCyan, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun EmptyState(onImport: () -> Unit) {
    var isDownloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    val animatedProgress by animateFloatAsState(targetValue = progress)

    LaunchedEffect(isDownloading) {
        if (isDownloading) {
            progress = 0f
            while (progress < 1f) {
                delay(50)
                progress += 0.01f
            }
            delay(500)
            isDownloading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Rounded.RocketLaunch, "", tint = AiriCyan, modifier = Modifier.size(80.dp))
        Spacer(Modifier.height(20.dp))
        
        val currentModel = ModelManager.getCurrent()
        Text(
            text = if (isDownloading) "جاري تجهيز AIRI..." else if (currentModel != null) "AIRI جاهزة للعمل" else "No Models Available", 
            color = Color.White, 
            fontSize = 18.sp, 
            fontWeight = FontWeight.Medium
        )
        
        Text(
            text = if (isDownloading) "يتم الآن تحميل ملفات الذكاء الاصطناعي المحلية" else if (currentModel != null) "النموذج النشط: ${currentModel.name}" else "قم بتنزيل نموذج أو استيراده لبدء الدردشة", 
            color = AiriTextSecondary, 
            fontSize = 14.sp
        )

        Spacer(Modifier.height(30.dp))

        if (isDownloading) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(250.dp)) {
                LinearProgressIndicator(
                    progress = animatedProgress,
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = AiriCyan,
                    backgroundColor = Color(0xFF1A1A1A)
                )
                Spacer(Modifier.height(10.dp))
                Text("${(animatedProgress * 100).toInt()}%", color = AiriCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Row {
                Button(
                    onClick = { isDownloading = true },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFDDDDDD)),
                    shape = RoundedCornerShape(25.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text("تنزيل النموذج", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onImport,
                    colors = ButtonDefaults.buttonColors(backgroundColor = AiriPanelBg),
                    shape = RoundedCornerShape(25.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AiriCyan)
                ) {
                    Text("استيراد نموذج", color = AiriCyan, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun NavItem(icon: ImageVector, label: String, textColor: Color = Color(0xFFAAAAAA), onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(15.dp))
        Text(label, color = textColor, fontSize = 16.sp)
    }
}

@Composable
fun ModelManagerScreen(onClose: () -> Unit, onImport: () -> Unit) {
    val models = ModelRegistry.getModels()
    Column(modifier = Modifier.fillMaxSize().background(AiriDarkBg).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowForward, "", tint = Color.White) }
            Text("إدارة النماذج", color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onImport) { Icon(Icons.Rounded.Add, "", tint = AiriCyan) }
        }
        Spacer(Modifier.height(20.dp))
        if (models.isEmpty()) {
            Text("لا توجد نماذج متوفرة حالياً", color = AiriTextSecondary, modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            models.forEach { model ->
                Card(
                    backgroundColor = AiriPanelBg,
                    shape = RoundedCornerShape(15.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { 
                        ModelManager.load(model) { /* Handle success/fail */ }
                    }
                ) {
                    Row(modifier = Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Layers, "", tint = if (ModelManager.getCurrent() == model) AiriCyan else Color.White)
                        Spacer(Modifier.width(15.dp))
                        Column {
                            Text(model.name, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("${model.source} - ${model.quantization}", color = AiriTextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(AiriDarkBg).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowForward, "", tint = Color.White) }
            Text("إعدادات أيري", color = Color.White, fontSize = 18.sp)
        }
        Spacer(Modifier.height(30.dp))
        SettingsCard("المظهر", Icons.Rounded.DarkMode)
        SettingsCard("اللغة", Icons.Rounded.Language)
        SettingsCard("الإبلاغ عن خطأ برمجي", Icons.Rounded.BugReport)
        SettingsCard("معلومات التطبيق", Icons.Rounded.Info)
    }
}

@Composable
fun SettingsCard(title: String, icon: ImageVector) {
    Card(
        backgroundColor = AiriPanelBg,
        shape = RoundedCornerShape(15.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White)
            Icon(icon, "", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
