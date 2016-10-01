package com.shootoff.plugins;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import com.shootoff.camera.Shot;
import com.shootoff.targets.Hit;
import com.shootoff.targets.Target;
import com.shootoff.util.TimerPool;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Dimension2D;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Shape;
import javafx.scene.text.Font;

public class StopShooting extends ProjectorTrainingExerciseBase implements TrainingExercise {
	private final List<TextField> textfields = new ArrayList<>();
	private final Map<Target, TextField> targetToRequiredHits = new HashMap<>();
	private final Map<Target, Integer> targetToCurrentHits = new HashMap<>();
	private final VBox exercisePane = new VBox();
	private final HBox textFieldsContainer = new HBox(10);

	private int minDelay = 4;
	private int maxDelay = 8;
	private long roundStartTime = 0;
	
	private boolean resetting = false;
	private final AtomicBoolean destroying = new AtomicBoolean(false);
	
	public StopShooting() {}

	public StopShooting(List<Target> targets) {
		super(targets);
	}

	@Override
	public void init() {
		addShootOFFButton("Reset Round", (event) -> {
			resetRoundTargets();
			roundStartTime = System.currentTimeMillis();
		});
		
		initRound();
		
		getDelayedStartInterval((min, max) -> {
			minDelay = min;
			maxDelay = max;
		});
		
		startRoundBeep();
	}
	
	private void initRound() {
		initTargets();
		initGui();
		assignRequiredHits();
	}
	
	private void startRoundBeep() {
		pauseShotDetection(true);
		
		final Random rand = new Random();
		final int delay = rand.nextInt((maxDelay - minDelay) + 1) + minDelay;
		TimerPool.schedule(() -> {
			if (destroying.get()) return;
			
			TrainingExerciseBase.playSound("sounds/beep.wav");
			this.pauseShotDetection(false);
			roundStartTime = System.currentTimeMillis();
		}, delay * 1000);
	}

	private void initTargets() {
		textfields.clear();
		targetToRequiredHits.clear();
		targetToCurrentHits.clear();
		
		final File stopShootingFile = new File("@target/stop_shooting.target");

		final Optional<Target> stopShootingTarget = addTarget(stopShootingFile, 10, 10);
		if (!stopShootingTarget.isPresent()) return;
		final TextField requiredHitsTextField = new TextField();
		requiredHitsTextField.textProperty().addListener(new NumberOnlyChangeListener(requiredHitsTextField));
		textfields.add(requiredHitsTextField);
		targetToRequiredHits.put(stopShootingTarget.get(), requiredHitsTextField);
		targetToCurrentHits.put(stopShootingTarget.get(), 0);

		// Determine if we can fit seven targets spaced equally apart on the
		// arena, otherwise use four
		final int targetGap = 10;

		final Dimension2D targetSize = stopShootingTarget.get().getDimension();

		final int targetCount;
		if (targetSize.getWidth() * 7 + targetGap * 6 > getArenaWidth()) {
			targetCount = 4;
		} else {
			targetCount = 7;
		}

		// Calculate the y-dimension to vertical center the targets
		final double targetY = (getArenaHeight() / 2) - (targetSize.getHeight() / 2);

		// Get the starting x-dimension required to horizontally center the
		// targets
		final double targetArrayWidth = (targetSize.getWidth() * targetCount) + (targetGap * (targetCount - 1));
		final double targetX = (getArenaWidth() - targetArrayWidth) / 2;

		// Add the remaining targets to the arena
		stopShootingTarget.get().setPosition(targetX, targetY);
		for (int i = 1; i < targetCount; i++) {
			final Optional<Target> t = addTarget(stopShootingFile, 
					targetX + (targetSize.getWidth() * i) + (targetGap * i), targetY);
			final TextField hitsTextField = new TextField();
			hitsTextField.textProperty().addListener(new NumberOnlyChangeListener(hitsTextField));
			if (t.isPresent()) {
				textfields.add(hitsTextField);
				targetToRequiredHits.put(t.get(), hitsTextField);
				targetToCurrentHits.put(t.get(), 0);
			}
		}
	}
	
	private class NumberOnlyChangeListener implements ChangeListener<String> {
		private final TextField observedTextField;

		public NumberOnlyChangeListener(TextField observedTextField) {
			this.observedTextField = observedTextField;
		}

		@Override
		public void changed(ObservableValue<? extends String> observable, String oldValue, String newValue) {
			if (!newValue.matches("\\d*")) {
				observedTextField.setText(oldValue);
				observedTextField.positionCaret(observedTextField.getLength());
			}

			if (oldValue.isEmpty()) oldValue = "0";
			if (newValue.isEmpty()) return;
			
			// If value change makes the target flip to fully shot
			// make it red, or not shot enough, make it brown again
			for (Entry<Target, TextField> entry : targetToRequiredHits.entrySet()) {
				if (entry.getValue().equals(observedTextField)) {
					final int newRequiredHits = Integer.valueOf(newValue);
					
					if (Integer.valueOf(oldValue) < newRequiredHits) {
						((Shape) entry.getKey().getRegions().get(0)).setFill(Color.SADDLEBROWN);
					} else if (Integer.valueOf(oldValue) > newRequiredHits && 
							targetToCurrentHits.get(entry.getKey()) >= newRequiredHits) {
						((Shape) entry.getKey().getRegions().get(0)).setFill(Color.DARKRED);
					}
					
					break;
				}
			}
		}
	}
	
	private void initGui() {
		final boolean previouslyInitialized = !exercisePane.getChildren().isEmpty();
		
		exercisePane.getChildren().clear();
		exercisePane.getChildren().add(new Label("Required Hits:"));
		
		textFieldsContainer.getChildren().clear();
		// We use the ArrayList of TextFields here to ensure that
		// the order of text fields on the pane matches the
		// order of the targets, otherwise the fields can be
		// in a random order due to the use of HashMaps
		for (TextField t : textfields) {
			t.setPrefWidth(50);
			textFieldsContainer.getChildren().add(t);
		}
		
		exercisePane.getChildren().add(textFieldsContainer);
		
		if (!previouslyInitialized) addExercisePane(exercisePane);
	}
	
	private void assignRequiredHits() {
		final Random rand = new Random();
		final int maxShots = 6;
		final int minShots = 1;
		
		for (TextField t : targetToRequiredHits.values()) {
			t.setText(String.valueOf(rand.nextInt((maxShots - minShots) + 1) + minShots));
		}
	}

	@Override
	public void reset(List<Target> targets) {
		resetting = true;
		
		for (Target t : targetToRequiredHits.keySet()) {
			removeTarget(t);
		}
		
		initRound();
		
		resetting = false;
	}

	@Override
	public void shotListener(Shot shot, Optional<Hit> hit) {
		if (hit.isPresent() && 
				targetToCurrentHits.containsKey(hit.get().getTarget())) {
			final Target hitTarget = hit.get().getTarget();
			
			int currentHits = targetToCurrentHits.get(hitTarget);
			currentHits++;
			targetToCurrentHits.put(hitTarget, currentHits);
			
			final int requiredHits = Integer.valueOf(targetToRequiredHits.get(hitTarget).getText());
			
			if (currentHits == requiredHits) {
				((Shape) hit.get().getHitRegion()).setFill(Color.DARKRED);
			} else if (currentHits > requiredHits) {
				// Reset all completed targets, shooter has to start over
				resetRoundTargets();
			}
		}
		
		boolean allTargetsComplete = true;
		for (Entry<Target, Integer> hitsEntry : targetToCurrentHits.entrySet()) {
			final int requiredHits = Integer.valueOf(targetToRequiredHits.get(hitsEntry.getKey()).getText());
			if (hitsEntry.getValue() < requiredHits) {
				allTargetsComplete = false;
				break;
			}
		}
		
		if (allTargetsComplete) {
			final double roundTime = (double) (System.currentTimeMillis() - roundStartTime) / 1000d;
			showTextOnFeed(String.format("%.2f s", roundTime), 25, 25, Color.BLACK, Color.RED, 
					new Font(Font.getDefault().getFamily(), 40));
			resetRoundTargets();
			assignRequiredHits();
			startRoundBeep();
		}
	}
	
	private void resetRoundTargets() {
		// Reset target colors and hits counts
		for (Entry<Target, Integer> hitsEntry : targetToCurrentHits.entrySet()) {
			((Shape) hitsEntry.getKey().getRegions().get(0)).setFill(Color.SADDLEBROWN);
			hitsEntry.setValue(0);
		}
	}

	@Override
	public void targetUpdate(Target target, TargetChange change) {
		// If the user deletes one of stop shooting targets, remove
		// it from the list and remove its text box
		if (TargetChange.REMOVED.equals(change) && 
				targetToRequiredHits.containsKey(target)) {
			textFieldsContainer.getChildren().remove(targetToRequiredHits.get(target));
			if (!resetting) targetToRequiredHits.remove(target);
			
			// If all targets are removed, start fresh
			if (!resetting && !destroying.get() && targetToRequiredHits.isEmpty()) {
				initRound();
			}
		}
	}

	@Override
	public ExerciseMetadata getInfo() {
		return new ExerciseMetadata("Stop Shooting", "1.0", "phrack",
				"This exercise is used to practice ceasing fire on a visual signal. An array of "
						+ "brown targets are display on the projector arena. Each has a random number between one and "
						+ "six assigned to it. This is the number of times the target must be shot before it signals "
						+ "that it should not be shot anymore by changing colors to red. If you shoot a red target, "
						+ "you must start over with the same number assignment. After all targets are red, the round ends "
						+ "and you are given the amount of time it too you to complete all targets. Manually enter "
						+ "required shot counts instead of relying on generated numbers to compete against your friends.");
	}
	
	@Override
	public void destroy() {
		destroying.set(true);
		super.destroy();
	}
}
