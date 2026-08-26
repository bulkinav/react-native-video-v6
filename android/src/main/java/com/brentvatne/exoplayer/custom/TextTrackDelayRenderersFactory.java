package com.brentvatne.exoplayer.custom;

import android.content.Context;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.text.TextTrackDelayRenderer;

import java.util.ArrayList;

/** Создаёт renderer субтитров с настраиваемым временным смещением. */
public final class TextTrackDelayRenderersFactory extends DefaultRenderersFactory {
  private long textTrackDelayUs;
  private TextTrackDelayRenderer textRenderer;

  public TextTrackDelayRenderersFactory(Context context, long textTrackDelayUs) {
    super(context);
    this.textTrackDelayUs = textTrackDelayUs;
  }

  /** Обновляет смещение для текущего и следующих renderer субтитров. */
  public void setTextTrackDelayUs(long textTrackDelayUs) {
    this.textTrackDelayUs = textTrackDelayUs;
    if (textRenderer != null) {
      textRenderer.setTextOffsetUs(textTrackDelayUs);
    }
  }

  @Override
  protected void buildTextRenderers(
          @NonNull Context context,
          @NonNull TextOutput output,
          @NonNull Looper outputLooper,
          @ExtensionRendererMode int extensionRendererMode,
          @NonNull ArrayList<Renderer> out) {
    textRenderer = new TextTrackDelayRenderer(output, outputLooper);
    textRenderer.setTextOffsetUs(textTrackDelayUs);
    out.add(textRenderer);
  }
}
