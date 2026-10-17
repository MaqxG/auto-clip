package com.autoclip;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AutoClipPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AutoClipPlugin.class);
		RuneLite.main(args);
	}

	@Test
	public void testClipRecorderCircularBufferAndMp4Export() throws Exception
	{
		ClipRecorder recorder = new ClipRecorder();
		long baseTime = 1_000_000L;

		for (int i = 0; i < 30; i++)
		{
			BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			g.setColor(new Color(20, 30, 40));
			g.fillRect(0, 0, 320, 240);
			g.setColor(Color.GREEN);
			g.fillRect(10 + i * 4, 40, 32, 32);
			g.dispose();

			recorder.recordFrame(img, baseTime + i * 100L, 10, 10);
		}

		assertTrue(recorder.getBufferedFrameCount() > 0);

		ByteArrayOutputStream mp4Out = new ByteArrayOutputStream();
		boolean exported = recorder.exportClip(mp4Out, baseTime + 500L, baseTime + 2900L, 10);
		assertTrue(exported);

		byte[] mp4Bytes = mp4Out.toByteArray();
		assertTrue(mp4Bytes.length > 100);
		String headerTag = new String(mp4Bytes, 4, 4, StandardCharsets.US_ASCII);
		assertEquals("ftyp", headerTag);

		recorder.clear();
		assertEquals(0, recorder.getBufferedFrameCount());
		assertFalse(recorder.exportClip(new ByteArrayOutputStream(), baseTime, baseTime + 1000L, 10));
	}

	@Test
	public void testQuestWidgetParsing()
	{
		assertEquals(
			"Quest(Cook's Assistant)",
			AutoClipPlugin.parseQuestCompletedWidget("You have completed the Cook's Assistant Quest!")
		);
		assertEquals(
			"Quest(Doric's Quest)",
			AutoClipPlugin.parseQuestCompletedWidget("You have completed Doric's Quest!")
		);
		assertEquals(
			"Quest(Recipe for Disaster - Another Cook's Quest)",
			AutoClipPlugin.parseQuestCompletedWidget("You have completed Another Cook's Quest!")
		);
	}

	@Test
	public void testBAHighGambleParsing()
	{
		assertEquals(
			"High Gamble(42)",
			AutoClipPlugin.parseBAHighGambleWidget("Dragon chainbody!<br>High level gamble count: <col=7f0000>42</col>")
		);
	}

	@Test
	public void testCombatAchievementParsing()
	{
		assertEquals(
			"Combat task (Grandmaster Task)",
			AutoClipPlugin.parseCombatAchievementWidget("Congratulations, you've completed a grandmaster combat task: @gre@Grandmaster: Task?</col>")
		);
	}

	@Test
	public void testSanitizeFileName()
	{
		assertEquals("Attack(99) 2026-10-17_02-00-00", AutoClipPlugin.sanitizeFileName("Attack(99) 2026-10-17_02-00-00"));
		assertEquals("Combat_task_(Test)", AutoClipPlugin.sanitizeFileName("Combat:task*(Test)... "));
		assertEquals("_con", AutoClipPlugin.sanitizeFileName("con"));
	}
}
