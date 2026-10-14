package com.volmit.staticstats.season;

public record SeasonCompletionEvent(SeasonRankingSnapshot snapshot, boolean reconciledAfterRestart) { }
