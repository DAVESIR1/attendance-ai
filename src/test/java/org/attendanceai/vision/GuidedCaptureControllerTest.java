/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

/**
 * The guided multi-angle state machine, driven with synthetic pose sequences
 * (the "fake landmark sequences" of the spec) rather than real landmarks, so
 * every threshold and hold-count rule is exercised deterministically:
 *
 *  - a pose held for only 2 frames must NOT capture;
 *  - the same pose held for 3+ frames must capture exactly once;
 *  - losing the pose resets the hold counter;
 *  - the blink step needs a held-shut eye plus a reopen inside the window;
 *  - the no-face hint is passive (it never fails the flow).
 */
public class GuidedCaptureControllerTest {

    /** Frame timestamp step used by the synthetic sequences (~30 fps). */
    private static final long FRAME_MS = 33L;

    private static PoseSample up() {
        return PoseSample.of(0f, -25f, 0.30f);
    }

    private static PoseSample down() {
        return PoseSample.of(0f, 25f, 0.30f);
    }

    private static PoseSample left() {
        return PoseSample.of(-25f, 0f, 0.30f);
    }

    private static PoseSample right() {
        return PoseSample.of(25f, 0f, 0.30f);
    }

    private static PoseSample neutral() {
        return PoseSample.of(0f, 0f, 0.30f);
    }

    private static PoseSample closedEyes() {
        return PoseSample.of(0f, 0f, 0.12f);
    }

    private static PoseSample openEyes() {
        return PoseSample.of(0f, 0f, 0.32f);
    }

    /** Feeds one sample {@code times} times, returning the LAST update. */
    private static GuidedCaptureController.Update feed(GuidedCaptureController controller,
            PoseSample sample, long startMs, int times) {
        GuidedCaptureController.Update last = null;
        for (int i = 0; i < times; i++) {
            last = controller.onFrame(sample, startMs + i * FRAME_MS);
        }
        return last;
    }

    /** Captures one pose step the legitimate way (3 held frames). */
    private static void hold(GuidedCaptureController controller, PoseSample sample, long startMs) {
        GuidedCaptureController.Update update =
                feed(controller, sample, startMs, GuidedCaptureController.HOLD_FRAMES);
        assertTrue("expected the pose to be captured", update.captured);
    }

    @Test
    public void startsOnLookUpWithInstructionAndProgress() {
        GuidedCaptureController controller = new GuidedCaptureController();

        assertEquals(CaptureStep.LOOK_UP, controller.currentStep());
        assertEquals("Look up", controller.currentStep().instruction());
        assertEquals("Step 1 of 5", GuidedCaptureController.progressText(controller.currentStep()));
        assertEquals(0, controller.capturedCount());
        assertFalse(controller.isComplete());
    }

    @Test
    public void poseHeldForTwoFramesDoesNotCapture() {
        GuidedCaptureController controller = new GuidedCaptureController();

        GuidedCaptureController.Update first = controller.onFrame(up(), 0L);
        GuidedCaptureController.Update second = controller.onFrame(up(), FRAME_MS);

        assertFalse(first.captured);
        assertFalse(second.captured);
        assertTrue(controller.captures().isEmpty());
        assertEquals(CaptureStep.LOOK_UP, controller.currentStep());
    }

    @Test
    public void poseHeldForThreeFramesCapturesAndAdvances() {
        GuidedCaptureController controller = new GuidedCaptureController();

        controller.onFrame(up(), 0L);
        controller.onFrame(up(), FRAME_MS);
        GuidedCaptureController.Update third = controller.onFrame(up(), 2 * FRAME_MS);

        assertTrue(third.captured);
        assertEquals(CaptureStep.LOOK_UP, third.capturedStep);
        assertEquals(CaptureStep.LOOK_DOWN, third.step);
        assertEquals("Look down", third.instruction);
        assertEquals("Step 2 of 5", third.progress);
        assertEquals(1, controller.capturedCount());
    }

    @Test
    public void losingThePoseResetsTheHoldCounter() {
        GuidedCaptureController controller = new GuidedCaptureController();

        controller.onFrame(up(), 0L);
        controller.onFrame(up(), FRAME_MS);
        assertFalse(controller.onFrame(neutral(), 2 * FRAME_MS).captured);

        // A fresh three-frame hold is required.
        assertFalse(controller.onFrame(up(), 3 * FRAME_MS).captured);
        assertFalse(controller.onFrame(up(), 4 * FRAME_MS).captured);
        assertTrue(controller.onFrame(up(), 5 * FRAME_MS).captured);
    }

    @Test
    public void leftAndRightUseOppositeYawSigns() {
        GuidedCaptureController controller = new GuidedCaptureController();

        // Still on LOOK_UP: a sideways turn must not satisfy it.
        assertFalse(feed(controller, left(), 0L, 4).captured);
        hold(controller, up(), 200L);       // LOOK_UP
        hold(controller, down(), 400L);     // LOOK_DOWN

        assertEquals(CaptureStep.LOOK_LEFT, controller.currentStep());
        // LOOK_LEFT needs a negative yaw; a positive one must not satisfy it.
        assertFalse(feed(controller, right(), 600L, 4).captured);
        assertTrue(feed(controller, left(), 800L, 3).captured);
    }

    @Test
    public void fullSequenceCapturesAllFiveStepsInOrder() {
        GuidedCaptureController controller = new GuidedCaptureController();

        hold(controller, up(), 0L);
        hold(controller, down(), 100L);
        hold(controller, left(), 200L);
        hold(controller, right(), 300L);
        // Blink: shut for three frames, then reopen inside the window.
        feed(controller, closedEyes(), 400L, 3);
        GuidedCaptureController.Update blink = controller.onFrame(openEyes(), 500L);

        assertTrue(blink.captured);
        assertEquals(CaptureStep.BLINK, blink.capturedStep);
        assertTrue(controller.isComplete());
        assertNull(controller.currentStep());
        assertEquals(Arrays.asList(CaptureStep.LOOK_UP, CaptureStep.LOOK_DOWN,
                        CaptureStep.LOOK_LEFT, CaptureStep.LOOK_RIGHT, CaptureStep.BLINK),
                controller.captures());
    }

    @Test
    public void blinkNeedsThreeShutFramesAndAReopen() {
        GuidedCaptureController controller = new GuidedCaptureController();
        hold(controller, up(), 0L);
        hold(controller, down(), 100L);
        hold(controller, left(), 200L);
        hold(controller, right(), 300L);
        assertEquals(CaptureStep.BLINK, controller.currentStep());

        // Only two shut frames, then reopening: not a blink.
        feed(controller, closedEyes(), 400L, 2);
        assertFalse(controller.onFrame(openEyes(), 500L).captured);
        assertTrue(controller.captures().size() == 4);

        // Three shut frames, then reopening: captured.
        feed(controller, closedEyes(), 600L, 3);
        assertTrue(controller.onFrame(openEyes(), 700L).captured);
    }

    @Test
    public void blinkMustReopenInsideTheWindow() {
        GuidedCaptureController controller = new GuidedCaptureController();
        hold(controller, up(), 0L);
        hold(controller, down(), 100L);
        hold(controller, left(), 200L);
        hold(controller, right(), 300L);

        feed(controller, closedEyes(), 400L, 3);
        // Reopening 1.5 s after first shutting is too late for a blink.
        assertFalse(controller.onFrame(openEyes(), 400L + 1500L).captured);
        assertFalse(controller.isComplete());
    }

    @Test
    public void noFaceHintAppearsAfterFiveSecondsAndIsPassive() {
        GuidedCaptureController controller = new GuidedCaptureController();

        assertFalse(controller.onFrame(PoseSample.none(), 0L).noFaceHint);
        assertFalse(controller.onFrame(PoseSample.none(), 4999L).noFaceHint);
        GuidedCaptureController.Update hint = controller.onFrame(PoseSample.none(), 5200L);
        assertTrue(hint.noFaceHint);
        assertEquals(GuidedCaptureController.NO_FACE_HINT, "keep your face in frame");
        // The flow simply waits: nothing was captured and no step was skipped.
        assertFalse(hint.captured);
        assertEquals(CaptureStep.LOOK_UP, controller.currentStep());
        assertTrue(controller.captures().isEmpty());

        // Seeing a face again clears the hint.
        assertFalse(controller.onFrame(neutral(), 5400L).noFaceHint);
    }

    @Test
    public void incompleteBlinkDoesNotPoisonTheNextAttempt() {
        GuidedCaptureController controller = new GuidedCaptureController();
        hold(controller, up(), 0L);
        hold(controller, down(), 100L);
        hold(controller, left(), 200L);
        hold(controller, right(), 300L);

        // Two shut frames, then a full reopen: not a blink.
        feed(controller, closedEyes(), 400L, 2);
        assertFalse(controller.onFrame(openEyes(), 500L).captured);

        // A proper blink later still counts, even though the earlier partial
        // shut began more than one blink window before it.
        feed(controller, closedEyes(), 1500L, 3);
        assertTrue(controller.onFrame(openEyes(), 1600L).captured);
        assertTrue(controller.isComplete());
    }

    @Test
    public void multipleFacesRaiseTheWarning() {
        GuidedCaptureController controller = new GuidedCaptureController();

        assertFalse(controller.onFrame(PoseSample.of(0f, -25f, 0.3f, 1), 0L).multiFaceWarning);
        GuidedCaptureController.Update two =
                controller.onFrame(PoseSample.of(0f, -25f, 0.3f, 2), FRAME_MS);
        assertTrue(two.multiFaceWarning);
        assertEquals(GuidedCaptureController.MULTI_FACE_HINT,
                "multiple faces detected, using the largest");
    }

    @Test
    public void eyeStateAloneDoesNotSatisfyAPoseStep() {
        GuidedCaptureController controller = new GuidedCaptureController();

        // Eyes shut while the head is straight: LOOK_UP must stay unsatisfied.
        assertFalse(feed(controller, closedEyes(), 0L, 5).captured);
        assertEquals(CaptureStep.LOOK_UP, controller.currentStep());
    }

    @Test
    public void completionIsStickyOnceEveryStepIsCaptured() {
        GuidedCaptureController controller = new GuidedCaptureController();
        hold(controller, up(), 0L);
        hold(controller, down(), 100L);
        hold(controller, left(), 200L);
        hold(controller, right(), 300L);
        feed(controller, closedEyes(), 400L, 3);
        controller.onFrame(openEyes(), 500L);

        GuidedCaptureController.Update after = controller.onFrame(openEyes(), 600L);
        assertTrue(after.complete);
        assertNull(after.step);
        assertEquals(5, controller.capturedCount());
    }
}