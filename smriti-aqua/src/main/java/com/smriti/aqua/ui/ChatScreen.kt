package com.smriti.aqua.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smriti.aqua.memory.RecallAnswer

private val suggestions = listOf(
    "When did it start?",
    "Has this happened before?",
    "Are you sure it's a leak?",
)

/** Ask tab: grounded Q&A against the episodic store. Answers cite event evidence. */
@Composable
fun ChatScreen(vm: ScanViewModel) {
    val chat by vm.chat.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size * 2)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Ask SMRITI",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Answers come only from stored events — never invented.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    suggestions.forEach { s ->
                        AssistChip(
                            onClick = { vm.ask(s) },
                            label = { Text(s, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
            chat.forEachIndexed { index, (question, answer) ->
                item(key = "q-$index") { QuestionBubble(question) }
                item(key = "a-$index") { AnswerCard(answer) }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask about what it heard…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    vm.ask(input)
                    input = ""
                },
                enabled = input.isNotBlank(),
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Send", tint = Amber)
            }
        }
    }
}

@Composable
private fun QuestionBubble(question: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = AmberSoft,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
        ) {
            Text(
                question,
                style = MaterialTheme.typography.bodyMedium,
                color = AmberDeep,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AnswerCard(answer: RecallAnswer) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "SMRITI",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Amber,
                    modifier = Modifier.weight(1f),
                )
                if (answer.grounded) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MossGreen,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(
                        "From memory",
                        style = MaterialTheme.typography.labelSmall,
                        color = MossGreen,
                    )
                }
            }
            Text(answer.text, style = MaterialTheme.typography.bodyMedium)
            if (answer.evidenceIds.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    answer.evidenceIds.forEach { id ->
                        Surface(color = SlateGreySoft, shape = RoundedCornerShape(50)) {
                            Text(
                                "Event $id",
                                style = MaterialTheme.typography.labelMedium,
                                color = SlateGrey,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
