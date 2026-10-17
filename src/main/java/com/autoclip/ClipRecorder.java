package com.autoclip;

import com.autoclip.Mp4Encoder.EncodedSample;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class ClipRecorder
{
	private static final int MAX_DIMENSION_WIDTH = 1280;
	private static final int MAX_DIMENSION_HEIGHT = 720;
	private static final long PAUSE_GAP_RESET_MS = 1500L;
	private static final long EXTRA_RETENTION_MS = 12_000L;

	private static final class GopChunk
	{
		private final int width;
		private final int height;
		private final List<EncodedSample> samples = new ArrayList<>();

		GopChunk(int width, int height)
		{
			this.width = width;
			this.height = height;
		}

		long getFirstTimestampMs()
		{
			return samples.isEmpty() ? 0L : samples.get(0).getTimestampMs();
		}

		long getLastTimestampMs()
		{
			return samples.isEmpty() ? 0L : samples.get(samples.size() - 1).getTimestampMs();
		}
	}

	private final Deque<GopChunk> gopBuffer = new ArrayDeque<>();

	private int currentWidth;
	private int currentHeight;
	private int[] rgbBuffer;
	private byte[] curMbs;
	private byte[] refMbs;
	private GopChunk activeGop;
	private long lastFrameTimestampMs;
	private int idrCounter;

	public synchronized void recordFrame(BufferedImage image, long timestampMs, int clipDurationSec, int fps)
	{
		if (image == null)
		{
			return;
		}

		int[] dims = computeTargetDimensions(image.getWidth(), image.getHeight());
		int targetW = dims[0];
		int targetH = dims[1];
		if (targetW < 2 || targetH < 2)
		{
			return;
		}

		int mbW = (targetW + 15) / 16;
		int mbH = (targetH + 15) / 16;
		int mbCount = mbW * mbH;

		boolean resolutionChanged = (targetW != currentWidth || targetH != currentHeight);
		if (resolutionChanged || rgbBuffer == null || curMbs == null || refMbs == null)
		{
			currentWidth = targetW;
			currentHeight = targetH;
			rgbBuffer = new int[targetW * targetH];
			curMbs = new byte[mbCount * 384];
			refMbs = new byte[mbCount * 384];
			activeGop = null;
		}

		Mp4Encoder.extractMacroblocks(image, targetW, targetH, rgbBuffer, curMbs);

		int gopMaxFrames = Math.max(5, Math.min(30, fps));
		boolean gapReset = (lastFrameTimestampMs > 0 && (timestampMs - lastFrameTimestampMs) > PAUSE_GAP_RESET_MS);
		boolean needNewGop = (activeGop == null || resolutionChanged || gapReset || activeGop.samples.size() >= gopMaxFrames);

		try
		{
			if (needNewGop)
			{
				byte[] avcc = Mp4Encoder.encodeIdrSlice(curMbs, refMbs, mbCount, idrCounter++);
				EncodedSample sample = new EncodedSample(timestampMs, targetW, targetH, true, avcc);
				activeGop = new GopChunk(targetW, targetH);
				activeGop.samples.add(sample);
				gopBuffer.addLast(activeGop);
			}
			else
			{
				int frameNum = activeGop.samples.size();
				byte[] avcc = Mp4Encoder.encodePSlice(curMbs, refMbs, mbCount, frameNum);
				EncodedSample sample = new EncodedSample(timestampMs, targetW, targetH, false, avcc);
				activeGop.samples.add(sample);
			}
			lastFrameTimestampMs = timestampMs;
		}
		catch (IOException e)
		{
			log.debug("Failed to encode frame into replay buffer", e);
			return;
		}

		long cutoffMs = timestampMs - (Math.max(3, clipDurationSec) * 1000L + EXTRA_RETENTION_MS);
		while (gopBuffer.size() > 1)
		{
			GopChunk head = gopBuffer.peekFirst();
			if (head != null && head.getLastTimestampMs() < cutoffMs)
			{
				gopBuffer.pollFirst();
			}
			else
			{
				break;
			}
		}
	}

	public boolean exportClip(OutputStream out, long startTimeMs, long endTimeMs, int fps) throws IOException
	{
		List<EncodedSample> snapshot;
		synchronized (this)
		{
			snapshot = selectSamplesLocked(startTimeMs, endTimeMs);
		}

		if (snapshot.isEmpty())
		{
			return false;
		}

		Mp4Encoder.writeMp4(out, snapshot, fps);
		return true;
	}

	public synchronized void clear()
	{
		gopBuffer.clear();
		activeGop = null;
		lastFrameTimestampMs = 0L;
	}

	public synchronized int getBufferedFrameCount()
	{
		int count = 0;
		for (GopChunk gop : gopBuffer)
		{
			count += gop.samples.size();
		}
		return count;
	}

	private List<EncodedSample> selectSamplesLocked(long startTimeMs, long endTimeMs)
	{
		if (gopBuffer.isEmpty())
		{
			return new ArrayList<>();
		}

		List<GopChunk> gops = new ArrayList<>(gopBuffer);
		// Find the last GOP within <= endTimeMs to determine the target resolution
		int targetW = -1;
		int targetH = -1;
		int lastGopIdx = -1;
		for (int i = gops.size() - 1; i >= 0; i--)
		{
			GopChunk g = gops.get(i);
			if (!g.samples.isEmpty() && g.getFirstTimestampMs() <= endTimeMs)
			{
				targetW = g.width;
				targetH = g.height;
				lastGopIdx = i;
				break;
			}
		}

		if (lastGopIdx < 0)
		{
			return new ArrayList<>();
		}

		// Walk backwards from lastGopIdx to find the earliest GOP matching resolution and covering startTimeMs
		int firstGopIdx = lastGopIdx;
		while (firstGopIdx > 0)
		{
			GopChunk prev = gops.get(firstGopIdx - 1);
			if (prev.width != targetW || prev.height != targetH)
			{
				break;
			}
			if (prev.getLastTimestampMs() < startTimeMs)
			{
				break;
			}
			firstGopIdx--;
		}

		List<EncodedSample> selected = new ArrayList<>();
		for (int i = firstGopIdx; i <= lastGopIdx; i++)
		{
			GopChunk g = gops.get(i);
			for (EncodedSample s : g.samples)
			{
				if (s.getTimestampMs() <= endTimeMs)
				{
					selected.add(s);
				}
			}
		}

		return selected;
	}

	static int[] computeTargetDimensions(int srcW, int srcH)
	{
		if (srcW <= 0 || srcH <= 0)
		{
			return new int[]{0, 0};
		}
		double scale = 1.0;
		if (srcW > MAX_DIMENSION_WIDTH || srcH > MAX_DIMENSION_HEIGHT)
		{
			scale = Math.min((double) MAX_DIMENSION_WIDTH / srcW, (double) MAX_DIMENSION_HEIGHT / srcH);
		}
		int w = Math.max(2, (int) Math.round(srcW * scale) & ~1);
		int h = Math.max(2, (int) Math.round(srcH * scale) & ~1);
		return new int[]{w, h};
	}
}
