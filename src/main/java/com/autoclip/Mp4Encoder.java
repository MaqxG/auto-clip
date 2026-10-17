package com.autoclip;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Pure-Java H.264 Constrained Baseline (avc1) + ISO Base Media File Format (.mp4) encoder.
 * Uses I_PCM macroblocks and P_Skip inter-frame compression with zero external dependencies,
 * reflection, or native code.
 */
public final class Mp4Encoder
{
	private static final int TIMESCALE = 1000;
	private static final int[] UNITY_MATRIX = {
		0x00010000, 0, 0,
		0, 0x00010000, 0,
		0, 0, 0x40000000
	};

	private Mp4Encoder()
	{
	}

	public static final class EncodedSample
	{
		private final long timestampMs;
		private final int width;
		private final int height;
		private final boolean keyframe;
		private final byte[] avccData;

		public EncodedSample(long timestampMs, int width, int height, boolean keyframe, byte[] avccData)
		{
			this.timestampMs = timestampMs;
			this.width = width;
			this.height = height;
			this.keyframe = keyframe;
			this.avccData = avccData;
		}

		public long getTimestampMs()
		{
			return timestampMs;
		}

		public int getWidth()
		{
			return width;
		}

		public int getHeight()
		{
			return height;
		}

		public boolean isKeyframe()
		{
			return keyframe;
		}

		public byte[] getAvccData()
		{
			return avccData;
		}
	}

	/**
	 * Extracts YUV420p macroblocks (384 bytes per 16x16 macroblock: 256 Y + 64 U + 64 V)
	 * from the given BufferedImage.
	 */
	public static void extractMacroblocks(BufferedImage source, int targetWidth, int targetHeight, int[] rgbBuffer, byte[] outMbs)
	{
		BufferedImage img = source;
		if (source.getWidth() != targetWidth || source.getHeight() != targetHeight)
		{
			img = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
			g.dispose();
		}

		img.getRGB(0, 0, targetWidth, targetHeight, rgbBuffer, 0, targetWidth);

		int mbW = (targetWidth + 15) / 16;
		int mbH = (targetHeight + 15) / 16;

		for (int my = 0; my < mbH; my++)
		{
			int basePy = my * 16;
			for (int mx = 0; mx < mbW; mx++)
			{
				int basePx = mx * 16;
				int mbIdx = (my * mbW + mx) * 384;

				for (int y = 0; y < 16; y++)
				{
					int py = Math.min(basePy + y, targetHeight - 1);
					int rowOff = py * targetWidth;
					int lumaRowIdx = mbIdx + (y << 4);
					for (int x = 0; x < 16; x++)
					{
						int px = Math.min(basePx + x, targetWidth - 1);
						int rgb = rgbBuffer[rowOff + px];
						int r = (rgb >> 16) & 0xFF;
						int g = (rgb >> 8) & 0xFF;
						int b = rgb & 0xFF;
						int yVal = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
						outMbs[lumaRowIdx + x] = (byte) clampByte(yVal);
					}
				}

				int uBase = mbIdx + 256;
				int vBase = mbIdx + 320;
				for (int cy = 0; cy < 8; cy++)
				{
					int py = Math.min(basePy + (cy << 1), targetHeight - 1);
					int rowOff = py * targetWidth;
					int chromaRowOff = cy << 3;
					for (int cx = 0; cx < 8; cx++)
					{
						int px = Math.min(basePx + (cx << 1), targetWidth - 1);
						int rgb = rgbBuffer[rowOff + px];
						int r = (rgb >> 16) & 0xFF;
						int g = (rgb >> 8) & 0xFF;
						int b = rgb & 0xFF;
						int uVal = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
						int vVal = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
						outMbs[uBase + chromaRowOff + cx] = (byte) clampByte(uVal);
						outMbs[vBase + chromaRowOff + cx] = (byte) clampByte(vVal);
					}
				}
			}
		}
	}

	public static byte[] encodeIdrSlice(byte[] curMbs, byte[] refMbs, int mbCount, int idrPicId) throws IOException
	{
		BitWriter bw = new BitWriter(mbCount * 386 + 64);
		bw.writeUE(0); // first_mb_in_slice = 0
		bw.writeUE(7); // slice_type = 7 (I-slice)
		bw.writeUE(0); // pic_parameter_set_id = 0
		bw.writeBits(0, 8); // frame_num = 0 (8 bits)
		bw.writeUE(idrPicId & 1); // idr_pic_id
		bw.writeBit(0); // no_output_of_prior_pics_flag = 0
		bw.writeBit(0); // long_term_reference_flag = 0
		bw.writeSE(0); // slice_qp_delta = 0
		bw.writeUE(1); // disable_deblocking_filter_idc = 1

		for (int i = 0; i < mbCount; i++)
		{
			bw.writeUE(25); // I_PCM in I-slice
			bw.writeAlignedBytes(curMbs, i * 384, 384);
		}
		bw.writeRbspTrailingBits();

		System.arraycopy(curMbs, 0, refMbs, 0, mbCount * 384);
		return wrapAvccNal(bw.toNalUnit(0x65));
	}

	public static byte[] encodePSlice(byte[] curMbs, byte[] refMbs, int mbCount, int frameNum) throws IOException
	{
		BitWriter bw = new BitWriter(Math.max(256, mbCount * 64));
		bw.writeUE(0); // first_mb_in_slice = 0
		bw.writeUE(5); // slice_type = 5 (P-slice)
		bw.writeUE(0); // pic_parameter_set_id = 0
		bw.writeBits(frameNum & 0xFF, 8); // frame_num (8 bits)
		bw.writeBit(0); // num_ref_idx_active_override_flag = 0
		bw.writeBit(0); // ref_pic_list_modification_flag_l0 = 0
		bw.writeBit(0); // adaptive_ref_pic_marking_mode_flag = 0
		bw.writeSE(0); // slice_qp_delta = 0
		bw.writeUE(1); // disable_deblocking_filter_idc = 1

		int skipRun = 0;
		for (int i = 0; i < mbCount; i++)
		{
			int off = i * 384;
			if (isMacroblockSimilar(curMbs, refMbs, off))
			{
				skipRun++;
			}
			else
			{
				bw.writeUE(skipRun);
				skipRun = 0;
				bw.writeUE(30); // I_PCM in P-slice (25 + 5)
				bw.writeAlignedBytes(curMbs, off, 384);
				System.arraycopy(curMbs, off, refMbs, off, 384);
			}
		}
		if (skipRun > 0)
		{
			bw.writeUE(skipRun);
		}
		bw.writeRbspTrailingBits();
		return wrapAvccNal(bw.toNalUnit(0x41));
	}

	private static boolean isMacroblockSimilar(byte[] cur, byte[] ref, int off)
	{
		int totalDiff = 0;
		for (int i = 0; i < 384; i++)
		{
			int d = Math.abs((cur[off + i] & 0xFF) - (ref[off + i] & 0xFF));
			if (d > 3)
			{
				return false;
			}
			totalDiff += d;
			if (totalDiff > 192)
			{
				return false;
			}
		}
		return true;
	}

	private static byte[] wrapAvccNal(byte[] nal) throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream(nal.length + 4);
		DataOutputStream dos = new DataOutputStream(baos);
		dos.writeInt(nal.length);
		dos.write(nal);
		return baos.toByteArray();
	}

	public static byte[] buildSpsNal(int width, int height)
	{
		int mbW = (width + 15) / 16;
		int mbH = (height + 15) / 16;
		int cropRight = (mbW * 16 - width) / 2;
		int cropBottom = (mbH * 16 - height) / 2;

		BitWriter bw = new BitWriter(32);
		bw.writeBits(66, 8); // profile_idc = 66 (Baseline)
		bw.writeBits(0xC0, 8); // constraint_set0_flag = 1, constraint_set1_flag = 1
		bw.writeBits(40, 8); // level_idc = 40
		bw.writeUE(0); // seq_parameter_set_id = 0
		bw.writeUE(4); // log2_max_frame_num_minus4 = 4 (8-bit frame_num)
		bw.writeUE(2); // pic_order_cnt_type = 2
		bw.writeUE(1); // max_num_ref_frames = 1
		bw.writeBit(0); // gaps_in_frame_num_value_allowed_flag = 0
		bw.writeUE(mbW - 1);
		bw.writeUE(mbH - 1);
		bw.writeBit(1); // frame_mbs_only_flag = 1
		bw.writeBit(1); // direct_8x8_inference_flag = 1
		if (cropRight > 0 || cropBottom > 0)
		{
			bw.writeBit(1);
			bw.writeUE(0);
			bw.writeUE(cropRight);
			bw.writeUE(0);
			bw.writeUE(cropBottom);
		}
		else
		{
			bw.writeBit(0);
		}
		bw.writeBit(0); // vui_parameters_present_flag = 0
		bw.writeRbspTrailingBits();
		return bw.toNalUnit(0x67);
	}

	public static byte[] buildPpsNal()
	{
		BitWriter bw = new BitWriter(16);
		bw.writeUE(0); // pic_parameter_set_id = 0
		bw.writeUE(0); // seq_parameter_set_id = 0
		bw.writeBit(0); // entropy_coding_mode_flag = 0 (CAVLC)
		bw.writeBit(0); // bottom_field_pic_order_in_frame_present_flag = 0
		bw.writeUE(0); // num_slice_groups_minus1 = 0
		bw.writeUE(0); // num_ref_idx_l0_default_active_minus1 = 0
		bw.writeUE(0); // num_ref_idx_l1_default_active_minus1 = 0
		bw.writeBit(0); // weighted_pred_flag = 0
		bw.writeBits(0, 2); // weighted_bipred_idc = 0
		bw.writeSE(0); // pic_init_qp_minus26 = 0
		bw.writeSE(0); // pic_init_qs_minus26 = 0
		bw.writeSE(0); // chroma_qp_index_offset = 0
		bw.writeBit(1); // deblocking_filter_control_present_flag = 1
		bw.writeBit(0); // constrained_intra_pred_flag = 0
		bw.writeBit(0); // redundant_pic_cnt_present_flag = 0
		bw.writeRbspTrailingBits();
		return bw.toNalUnit(0x68);
	}

	/**
	 * Muxes a list of encoded H.264 samples (starting with a keyframe) into an MP4 stream.
	 */
	public static void writeMp4(OutputStream rawOut, List<EncodedSample> samples, int defaultFps) throws IOException
	{
		if (samples.isEmpty())
		{
			throw new IllegalArgumentException("Cannot write MP4 with no samples");
		}

		int width = samples.get(0).getWidth();
		int height = samples.get(0).getHeight();
		int defaultDeltaMs = Math.max(1, Math.round(1000.0f / Math.max(1, defaultFps)));

		int[] durationsMs = new int[samples.size()];
		int syncCount = 0;
		for (int i = 0; i < samples.size(); i++)
		{
			if (samples.get(i).isKeyframe())
			{
				syncCount++;
			}
			if (i + 1 < samples.size())
			{
				long dt = samples.get(i + 1).getTimestampMs() - samples.get(i).getTimestampMs();
				durationsMs[i] = (int) Math.max(10L, Math.min(500L, dt));
			}
			else
			{
				durationsMs[i] = defaultDeltaMs;
			}
		}

		int[] syncSampleNums = new int[syncCount];
		int sIdx = 0;
		for (int i = 0; i < samples.size(); i++)
		{
			if (samples.get(i).isKeyframe())
			{
				syncSampleNums[sIdx++] = i + 1;
			}
		}

		byte[] spsNal = buildSpsNal(width, height);
		byte[] ppsNal = buildPpsNal();

		ByteArrayOutputStream ftypBaos = new ByteArrayOutputStream(24);
		DataOutputStream ftypDos = new DataOutputStream(ftypBaos);
		ftypDos.write("isom".getBytes(StandardCharsets.US_ASCII));
		ftypDos.writeInt(0x00000200);
		ftypDos.write("isomiso2avc1mp41".getBytes(StandardCharsets.US_ASCII));
		byte[] ftypBox = box("ftyp", ftypBaos.toByteArray());

		byte[] dummyMoov = buildMoov(width, height, spsNal, ppsNal, samples, durationsMs, syncSampleNums, 0);
		int mdatPayloadOffset = ftypBox.length + dummyMoov.length + 8;
		byte[] moovBox = buildMoov(width, height, spsNal, ppsNal, samples, durationsMs, syncSampleNums, mdatPayloadOffset);

		int mdatSize = 8;
		for (EncodedSample sample : samples)
		{
			mdatSize += sample.getAvccData().length;
		}

		DataOutputStream out = new DataOutputStream(rawOut);
		out.write(ftypBox);
		out.write(moovBox);
		out.writeInt(mdatSize);
		out.write("mdat".getBytes(StandardCharsets.US_ASCII));
		for (EncodedSample sample : samples)
		{
			out.write(sample.getAvccData());
		}
		out.flush();
	}

	private static byte[] buildMoov(
		int width,
		int height,
		byte[] spsNal,
		byte[] ppsNal,
		List<EncodedSample> samples,
		int[] durationsMs,
		int[] syncSampleNums,
		int mdatPayloadOffset
	) throws IOException
	{
		int totalDuration = 0;
		for (int d : durationsMs)
		{
			totalDuration += d;
		}

		ByteArrayOutputStream mvhdBaos = new ByteArrayOutputStream(96);
		DataOutputStream mvhd = new DataOutputStream(mvhdBaos);
		mvhd.writeInt(0);
		mvhd.writeInt(0);
		mvhd.writeInt(TIMESCALE);
		mvhd.writeInt(totalDuration);
		mvhd.writeInt(0x00010000);
		mvhd.writeShort(0x0100);
		mvhd.write(new byte[10]);
		for (int m : UNITY_MATRIX)
		{
			mvhd.writeInt(m);
		}
		mvhd.write(new byte[24]);
		mvhd.writeInt(2);
		byte[] mvhdBox = fullBox("mvhd", 0, 0, mvhdBaos.toByteArray());

		ByteArrayOutputStream tkhdBaos = new ByteArrayOutputStream(84);
		DataOutputStream tkhd = new DataOutputStream(tkhdBaos);
		tkhd.writeInt(0);
		tkhd.writeInt(0);
		tkhd.writeInt(1);
		tkhd.writeInt(0);
		tkhd.writeInt(totalDuration);
		tkhd.write(new byte[8]);
		tkhd.writeShort(0);
		tkhd.writeShort(0);
		tkhd.writeShort(0);
		tkhd.writeShort(0);
		for (int m : UNITY_MATRIX)
		{
			tkhd.writeInt(m);
		}
		tkhd.writeInt(width << 16);
		tkhd.writeInt(height << 16);
		byte[] tkhdBox = fullBox("tkhd", 0, 0x000003, tkhdBaos.toByteArray());

		ByteArrayOutputStream mdhdBaos = new ByteArrayOutputStream(20);
		DataOutputStream mdhd = new DataOutputStream(mdhdBaos);
		mdhd.writeInt(0);
		mdhd.writeInt(0);
		mdhd.writeInt(TIMESCALE);
		mdhd.writeInt(totalDuration);
		mdhd.writeShort(0x55C4);
		mdhd.writeShort(0);
		byte[] mdhdBox = fullBox("mdhd", 0, 0, mdhdBaos.toByteArray());

		ByteArrayOutputStream hdlrBaos = new ByteArrayOutputStream(36);
		DataOutputStream hdlr = new DataOutputStream(hdlrBaos);
		hdlr.writeInt(0);
		hdlr.write("vide".getBytes(StandardCharsets.US_ASCII));
		hdlr.write(new byte[12]);
		hdlr.write("VideoHandler\0".getBytes(StandardCharsets.US_ASCII));
		byte[] hdlrBox = fullBox("hdlr", 0, 0, hdlrBaos.toByteArray());

		byte[] vmhdBox = fullBox("vmhd", 0, 1, new byte[8]);

		byte[] urlBox = fullBox("url ", 0, 1, new byte[0]);
		ByteArrayOutputStream drefBaos = new ByteArrayOutputStream(16);
		DataOutputStream dref = new DataOutputStream(drefBaos);
		dref.writeInt(1);
		dref.write(urlBox);
		byte[] dinfBox = box("dinf", fullBox("dref", 0, 0, drefBaos.toByteArray()));

		ByteArrayOutputStream avcCBaos = new ByteArrayOutputStream(32 + spsNal.length + ppsNal.length);
		DataOutputStream avcC = new DataOutputStream(avcCBaos);
		avcC.writeByte(1);
		avcC.writeByte(spsNal[1]);
		avcC.writeByte(spsNal[2]);
		avcC.writeByte(spsNal[3]);
		avcC.writeByte(0xFF);
		avcC.writeByte(0xE1);
		avcC.writeShort(spsNal.length);
		avcC.write(spsNal);
		avcC.writeByte(1);
		avcC.writeShort(ppsNal.length);
		avcC.write(ppsNal);
		byte[] avcCBox = box("avcC", avcCBaos.toByteArray());

		ByteArrayOutputStream avc1Baos = new ByteArrayOutputStream(86 + avcCBox.length);
		DataOutputStream avc1 = new DataOutputStream(avc1Baos);
		avc1.write(new byte[6]);
		avc1.writeShort(1);
		avc1.write(new byte[16]);
		avc1.writeShort(width);
		avc1.writeShort(height);
		avc1.writeInt(0x00480000);
		avc1.writeInt(0x00480000);
		avc1.writeInt(0);
		avc1.writeShort(1);
		avc1.write(new byte[32]);
		avc1.writeShort(0x0018);
		avc1.writeShort(-1);
		avc1.write(avcCBox);
		byte[] avc1Box = box("avc1", avc1Baos.toByteArray());

		ByteArrayOutputStream stsdBaos = new ByteArrayOutputStream(8 + avc1Box.length);
		DataOutputStream stsd = new DataOutputStream(stsdBaos);
		stsd.writeInt(1);
		stsd.write(avc1Box);
		byte[] stsdBox = fullBox("stsd", 0, 0, stsdBaos.toByteArray());

		ByteArrayOutputStream sttsBaos = new ByteArrayOutputStream(4 + durationsMs.length * 8);
		DataOutputStream stts = new DataOutputStream(sttsBaos);
		stts.writeInt(durationsMs.length);
		for (int d : durationsMs)
		{
			stts.writeInt(1);
			stts.writeInt(d);
		}
		byte[] sttsBox = fullBox("stts", 0, 0, sttsBaos.toByteArray());

		ByteArrayOutputStream stssBaos = new ByteArrayOutputStream(4 + syncSampleNums.length * 4);
		DataOutputStream stss = new DataOutputStream(stssBaos);
		stss.writeInt(syncSampleNums.length);
		for (int s : syncSampleNums)
		{
			stss.writeInt(s);
		}
		byte[] stssBox = fullBox("stss", 0, 0, stssBaos.toByteArray());

		ByteArrayOutputStream stscBaos = new ByteArrayOutputStream(16);
		DataOutputStream stsc = new DataOutputStream(stscBaos);
		stsc.writeInt(1);
		stsc.writeInt(1);
		stsc.writeInt(samples.size());
		stsc.writeInt(1);
		byte[] stscBox = fullBox("stsc", 0, 0, stscBaos.toByteArray());

		ByteArrayOutputStream stszBaos = new ByteArrayOutputStream(8 + samples.size() * 4);
		DataOutputStream stsz = new DataOutputStream(stszBaos);
		stsz.writeInt(0);
		stsz.writeInt(samples.size());
		for (EncodedSample sample : samples)
		{
			stsz.writeInt(sample.getAvccData().length);
		}
		byte[] stszBox = fullBox("stsz", 0, 0, stszBaos.toByteArray());

		ByteArrayOutputStream stcoBaos = new ByteArrayOutputStream(8);
		DataOutputStream stco = new DataOutputStream(stcoBaos);
		stco.writeInt(1);
		stco.writeInt(mdatPayloadOffset);
		byte[] stcoBox = fullBox("stco", 0, 0, stcoBaos.toByteArray());

		byte[] stblBox = box("stbl", concat(stsdBox, sttsBox, stssBox, stscBox, stszBox, stcoBox));
		byte[] minfBox = box("minf", concat(vmhdBox, dinfBox, stblBox));
		byte[] mdiaBox = box("mdia", concat(mdhdBox, hdlrBox, minfBox));
		byte[] trakBox = box("trak", concat(tkhdBox, mdiaBox));
		return box("moov", concat(mvhdBox, trakBox));
	}

	private static byte[] box(String type, byte[] payload) throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream(8 + payload.length);
		DataOutputStream dos = new DataOutputStream(baos);
		dos.writeInt(8 + payload.length);
		dos.write(type.getBytes(StandardCharsets.US_ASCII), 0, 4);
		dos.write(payload);
		return baos.toByteArray();
	}

	private static byte[] fullBox(String type, int version, int flags, byte[] payload) throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream(12 + payload.length);
		DataOutputStream dos = new DataOutputStream(baos);
		dos.writeInt(12 + payload.length);
		dos.write(type.getBytes(StandardCharsets.US_ASCII), 0, 4);
		dos.writeInt((version << 24) | (flags & 0xFFFFFF));
		dos.write(payload);
		return baos.toByteArray();
	}

	private static byte[] concat(byte[]... arrays)
	{
		int total = 0;
		for (byte[] a : arrays)
		{
			total += a.length;
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream(total);
		for (byte[] a : arrays)
		{
			baos.write(a, 0, a.length);
		}
		return baos.toByteArray();
	}

	private static int clampByte(int val)
	{
		return val < 0 ? 0 : (val > 255 ? 255 : val);
	}

	static final class BitWriter
	{
		private final ByteArrayOutputStream out;
		private int currentByte;
		private int bitCount;

		BitWriter(int initialCapacity)
		{
			this.out = new ByteArrayOutputStream(initialCapacity);
		}

		void writeBit(int bit)
		{
			currentByte = (currentByte << 1) | (bit & 1);
			bitCount++;
			if (bitCount == 8)
			{
				out.write(currentByte);
				currentByte = 0;
				bitCount = 0;
			}
		}

		void writeBits(int value, int numBits)
		{
			for (int i = numBits - 1; i >= 0; i--)
			{
				writeBit((value >>> i) & 1);
			}
		}

		void writeUE(int value)
		{
			int codeNum = value + 1;
			int leadingZeroBits = 31 - Integer.numberOfLeadingZeros(codeNum);
			for (int i = 0; i < leadingZeroBits; i++)
			{
				writeBit(0);
			}
			writeBits(codeNum, leadingZeroBits + 1);
		}

		void writeSE(int value)
		{
			int codeNum = (value <= 0) ? (-2 * value) : (2 * value - 1);
			writeUE(codeNum);
		}

		void alignWithZeros()
		{
			while (bitCount != 0)
			{
				writeBit(0);
			}
		}

		void writeAlignedBytes(byte[] data, int off, int len)
		{
			alignWithZeros();
			out.write(data, off, len);
		}

		void writeRbspTrailingBits()
		{
			writeBit(1);
			alignWithZeros();
		}

		byte[] toNalUnit(int nalHeader)
		{
			byte[] rbsp = out.toByteArray();
			ByteArrayOutputStream ebsp = new ByteArrayOutputStream(rbsp.length + 64);
			ebsp.write(nalHeader);
			int zeros = 0;
			for (int i = 0; i < rbsp.length; i++)
			{
				int b = rbsp[i] & 0xFF;
				if (zeros == 2 && b <= 3)
				{
					ebsp.write(3);
					zeros = 0;
				}
				ebsp.write(b);
				if (b == 0)
				{
					zeros++;
				}
				else
				{
					zeros = 0;
				}
			}
			return ebsp.toByteArray();
		}
	}
}
