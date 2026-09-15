package com.airi.assistant.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.theme.*

private data class PromptCandidate(val title: String, val body: String, val score: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptBuilderScreen(
    onBack: () -> Unit,
    onUsePrompt: (String) -> Unit,
    onDelegateToAgent: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var goal by rememberSaveable { mutableStateOf("") }
    var audience by rememberSaveable { mutableStateOf("") }
    var constraints by rememberSaveable { mutableStateOf("") }
    var selectedModel by rememberSaveable { mutableStateOf("Auto · أفضل نموذج للمهمة") }
    var selectedTone by rememberSaveable { mutableStateOf("احترافي وواضح") }
    var skills by rememberSaveable { mutableStateOf(setOf("التخطيط", "البحث")) }
    var connectors by rememberSaveable { mutableStateOf(setOf("الويب")) }
    var candidates by remember { mutableStateOf<List<PromptCandidate>>(emptyList()) }
    var chosen by remember { mutableStateOf<PromptCandidate?>(null) }
    var editing by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var pinned by rememberSaveable { mutableStateOf(false) }

    fun buildCandidate(variant: Int): PromptCandidate {
        val contextLine = if (audience.isBlank()) "للمستخدم النهائي" else "للجمهور التالي: $audience"
        val constraintLine = if (constraints.isBlank()) "اذكر الافتراضات وحدود النتيجة." else "التزم بهذه القيود: $constraints"
        val toolLine = "استفد عند الحاجة من المهارات: ${skills.joinToString()}، والموصلات: ${connectors.joinToString()}."
        val body = if (variant == 1) {
            "أنت مساعد $selectedTone. نفّذ المهمة التالية بدقة: $goal\n\n$contextLine. $constraintLine\n$toolLine\nقسّم العمل إلى خطوات قصيرة، تحقق من المعلومات، ثم قدم النتيجة بصيغة عملية مع ملخص وافتراضات وأسئلة متابعة عند الحاجة. لا تنفذ أي إجراء خارجي أو ترسل بيانات قبل طلب موافقة المستخدم."
        } else {
            "الدور: وكيل متخصص يساعد في إنجاز الهدف التالي: $goal\n\nابدأ بتحليل المطلوب وتحديد المدخلات الناقصة. اقترح خطة من ثلاث مراحل، ثم نفّذ ما يمكن تنفيذه. $contextLine. $constraintLine\nالأدوات المسموحة: ${skills.joinToString()} و${connectors.joinToString()}.\nاعرض خيارين عند وجود مفاضلة، واذكر مستوى الثقة ومصادر المعلومات. قبل الكتابة أو الإرسال أو الحذف أو أي أثر خارجي، اعرض معاينة واضحة وانتظر تأكيد المستخدم."
        }
        return PromptCandidate(if (variant == 1) "صيغة مركزة" else "صيغة وكيل متحقق", body, if (variant == 1) 86 else 92)
    }

    fun generate() {
        if (goal.isBlank()) return
        candidates = listOf(buildCandidate(1), buildCandidate(2))
        chosen = candidates.maxByOrNull { it.score }
        editing = false
    }

    Scaffold(
        containerColor = AiriTheme.background,
        topBar = {
            TopAppBar(
                title = { Column { Text(stringResource(R.string.prompt_builder_title), fontWeight = FontWeight.Bold); Text(stringResource(R.string.prompt_builder_subtitle), fontSize = 11.sp, color = AiriTheme.onSurfaceVariant) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = { pinned = !pinned }) { Icon(if (pinned) Icons.Outlined.PushPin else Icons.Outlined.PushPin, stringResource(R.string.prompt_pin)) }
                    IconButton(onClick = { showReport = true }) { Icon(Icons.Outlined.Flag, stringResource(R.string.prompt_report)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background)
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
            item {
                Surface(shape = AIRIShapes.xl, color = AiriTheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, AiriTheme.outline.copy(alpha = .45f))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.prompt_builder_goal), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
                        OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text(stringResource(R.string.prompt_builder_goal_hint)) }, shape = AIRIShapes.md)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PromptChoice(selectedModel, Icons.Outlined.Memory) { selectedModel = if (selectedModel.startsWith("Auto")) "نموذج سريع" else "Auto · أفضل نموذج للمهمة" }
                            PromptChoice(selectedTone, Icons.Outlined.Tune) { selectedTone = if (selectedTone.startsWith("احترافي")) "إبداعي ومباشر" else "احترافي وواضح" }
                        }
                        OutlinedTextField(audience, { audience = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.prompt_builder_audience)) }, leadingIcon = { Icon(Icons.Outlined.Groups, null) }, shape = AIRIShapes.md)
                        OutlinedTextField(constraints, { constraints = it }, Modifier.fillMaxWidth(), minLines = 2, label = { Text(stringResource(R.string.prompt_builder_constraints)) }, leadingIcon = { Icon(Icons.Outlined.Rule, null) }, shape = AIRIShapes.md)
                    }
                }
            }
            item { PromptSectionTitle(stringResource(R.string.prompt_builder_skills)); ChoiceChips(listOf("التخطيط", "البحث", "البرمجة", "الترجمة", "الذاكرة", "الوثائق"), skills) { skills = if (skills.contains(it)) skills - it else skills + it } }
            item { PromptSectionTitle(stringResource(R.string.prompt_builder_connectors)); ChoiceChips(listOf("الويب", "Google", "GitHub", "Telegram", "Notion", "الملفات"), connectors) { connectors = if (connectors.contains(it)) connectors - it else connectors + it } }
            item { Button(onClick = { generate() }, enabled = goal.isNotBlank(), modifier = Modifier.fillMaxWidth().height(54.dp), shape = AIRIShapes.lg, colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent)) { Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.prompt_builder_generate), fontWeight = FontWeight.Bold) } }
            if (candidates.isNotEmpty()) {
                item { PromptSectionTitle(stringResource(R.string.prompt_builder_compare)) }
                items(candidates, key = { it.title }) { candidate ->
                    PromptCandidateCard(candidate, selected = chosen?.title == candidate.title, editing = editing && chosen?.title == candidate.title, onSelect = { chosen = candidate }, onEdit = { editing = true }, onBodyChange = { updated -> candidates = candidates.map { if (it.title == candidate.title) it.copy(body = updated) else it }; chosen = candidates.firstOrNull { it.title == candidate.title } })
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { chosen?.let { onUsePrompt(it.body) } }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.prompt_use)) }
                        Button(onClick = { chosen?.let { onDelegateToAgent("حسّن هذا البرومبت مع الحفاظ على قصده:\n\n${it.body}") } }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent)) { Icon(Icons.Outlined.Psychology, null); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.prompt_agent_review)) }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { chosen?.let { context.getSharedPreferences("airi_prompt_library", 0).edit().putString(it.title, it.body).apply(); Toast.makeText(context, R.string.prompt_saved, Toast.LENGTH_SHORT).show() } }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.BookmarkBorder, null); Text(stringResource(R.string.prompt_save)) }
                        OutlinedButton(onClick = { chosen?.let { val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it.body); context.startActivity(Intent.createChooser(i, context.getString(R.string.prompt_share))) } }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Share, null); Text(stringResource(R.string.prompt_share)) }
                    }
                }
            }
        }
    }
    if (showReport) {
        var report by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showReport = false }, title = { Text(stringResource(R.string.prompt_report_title)) }, text = { OutlinedTextField(report, { report = it }, minLines = 4, placeholder = { Text(stringResource(R.string.prompt_report_hint)) }) }, confirmButton = { TextButton(onClick = { showReport = false; Toast.makeText(context, R.string.prompt_report_sent, Toast.LENGTH_SHORT).show() }) { Text(stringResource(R.string.prompt_send_report)) } }, dismissButton = { TextButton(onClick = { showReport = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable private fun PromptSectionTitle(text: String) { Text(text, fontWeight = FontWeight.Bold, color = AiriTheme.onBackground, modifier = Modifier.padding(top = 4.dp)) }

@Composable private fun androidx.compose.foundation.layout.RowScope.PromptChoice(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) { Surface(modifier = Modifier.weight(1f).clickable(onClick = onClick), shape = AIRIShapes.md, color = AiriTheme.surfaceVariant, border = androidx.compose.foundation.BorderStroke(1.dp, AiriTheme.outline.copy(alpha = .4f))) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = CosmicAccent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) } } }

@Composable private fun ChoiceChips(options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { options.take(4).forEach { label -> FilterChip(selected.contains(label), { onToggle(label) }, label = { Text(label, fontSize = 11.sp) }, shape = AIRIShapes.pill) } } }

@Composable private fun PromptCandidateCard(candidate: PromptCandidate, selected: Boolean, editing: Boolean, onSelect: () -> Unit, onEdit: () -> Unit, onBodyChange: (String) -> Unit) { Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect), shape = AIRIShapes.xl, color = if (selected) CosmicAccent.copy(alpha = .10f) else AiriTheme.surface, border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) CosmicAccent else AiriTheme.outline.copy(alpha = .35f))) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(28.dp).clip(CircleShape).background(if (selected) CosmicAccent else AiriTheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(if (selected) Icons.Outlined.Check else Icons.Outlined.AutoAwesome, null, tint = if (selected) AiriTheme.onPrimary else CosmicAccent, modifier = Modifier.size(16.dp)) }; Spacer(Modifier.width(10.dp)); Text(candidate.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("${candidate.score}%", color = SemanticSuccess, fontSize = 12.sp); IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.prompt_edit)) } }; if (editing) OutlinedTextField(candidate.body, onBodyChange, Modifier.fillMaxWidth(), minLines = 8) else Text(candidate.body, color = AiriTheme.onSurfaceVariant, lineHeight = 20.sp) } } }
