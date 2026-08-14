package com.mtzallqmy.aiagent.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** User/assistant message bubble. Code content is forced LTR with monospace font. */
@Composable
fun MessageBubble(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
) {
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = bubbleColor,
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            if (text.trimStart().startsWith("```")) {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = text,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!isUser && onCopy != null) {
                TextButton(
                    onClick = onCopy,
                    modifier = Modifier.align(Alignment.BottomEnd),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) { Text("Copy", fontSize = 10.sp) }
            }
        }
    }
}

/** Tool call card shown in chat timeline. */
@Composable
fun ToolCallCard(
    toolName: String,
    argumentsSummary: String,
    success: Boolean? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(toolName, style = MaterialTheme.typography.labelMedium)
                if (argumentsSummary.isNotBlank()) {
                    Text(argumentsSummary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
            if (success != null) {
                Icon(
                    if (success) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                    contentDescription = null,
                    tint = if (success) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Approval request card rendered in the chat stream. */
@Composable
fun ApprovalCard(
    toolName: String,
    action: String,
    target: String,
    riskLevel: String,
    argumentsSummary: String,
    onAllowOnce: () -> Unit,
    onAllowTask: () -> Unit,
    onAlwaysAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Approval required", style = MaterialTheme.typography.titleSmall)
            Text("Tool: $toolName", style = MaterialTheme.typography.bodySmall)
            Text("Action: $action → $target", style = MaterialTheme.typography.bodySmall)
            if (argumentsSummary.isNotBlank()) {
                Text(argumentsSummary, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            Text("Risk: $riskLevel", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onAllowOnce) { Text("Allow once", fontSize = 11.sp) }
                TextButton(onClick = onAllowTask) { Text("Allow task", fontSize = 11.sp) }
                TextButton(onClick = onAlwaysAllow) { Text("Always", fontSize = 11.sp) }
                TextButton(onClick = onDeny) { Text("Deny", fontSize = 11.sp) }
            }
        }
    }
}

/** Error card for typed errors. Never shows stack traces. */
@Composable
fun ErrorCard(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Error, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("Retry", fontSize = 11.sp) }
            }
        }
    }
}

/** Non-interactive status badge used for state display. */
@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
