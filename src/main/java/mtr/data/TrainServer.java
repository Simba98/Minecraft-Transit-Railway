package mtr.data;

import mtr.config.CustomResources;
import mtr.path.PathData;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

public class TrainServer extends Train {

	protected boolean canDeploy;
	private Set<UUID> trainPositions;
	private Map<PlayerEntity, Set<TrainServer>> trainsInPlayerRange = new HashMap<>();

	private static final float INNER_PADDING = 0.5F;
	private static final int BOX_PADDING = 3;

	public TrainServer(long id, long sidingId, float railLength, CustomResources.TrainMapping trainMapping, int trainLength, List<PathData> path, List<Float> distances) {
		super(id, sidingId, railLength, trainMapping, trainLength, path, distances);
	}

	public TrainServer(long sidingId, float railLength, List<PathData> path, List<Float> distances, NbtCompound nbtCompound) {
		super(sidingId, railLength, path, distances, nbtCompound);
	}

	@Override
	protected void simulateCar(
			World world, int ridingCar, float ticksElapsed,
			double carX, double carY, double carZ, float carYaw, float carPitch,
			double prevCarX, double prevCarY, double prevCarZ, float prevCarYaw, float prevCarPitch,
			boolean doorLeftOpen, boolean doorRightOpen, double realSpacing,
			float doorValueRaw, float oldSpeed, float oldDoorValue, float oldRailProgress
	) {
		final TrainType trainType = trainMapping.trainType;
		final float doorValue = Math.abs(doorValueRaw);
		final float halfSpacing = trainType.getSpacing() / 2F;
		final float halfWidth = trainType.width / 2F;

		final BlockPos soundPos = new BlockPos(carX, carY, carZ);
		trainType.playSpeedSoundEffect(world, soundPos, oldSpeed, speed);

		if (doorLeftOpen || doorRightOpen) {
			if (oldDoorValue <= 0 && doorValue > 0 && trainType.doorOpenSoundEvent != null) {
				world.playSound(null, soundPos, trainType.doorOpenSoundEvent, SoundCategory.BLOCKS, 1, 1);
			} else if (oldDoorValue >= trainType.doorCloseSoundTime && doorValue < trainType.doorCloseSoundTime && trainType.doorCloseSoundEvent != null) {
				world.playSound(null, soundPos, trainType.doorCloseSoundEvent, SoundCategory.BLOCKS, 1, 1);
			}

			final float margin = halfSpacing + BOX_PADDING;
			world.getEntitiesByClass(PlayerEntity.class, new Box(carX + margin, carY + margin, carZ + margin, carX - margin, carY - margin, carZ - margin), player -> !player.isSpectator() && !ridingEntities.contains(player.getUuid())).forEach(player -> {
				final Vec3d positionRotated = player.getPos().subtract(carX, carY, carZ).rotateY(-carYaw).rotateX(-carPitch);
				if (Math.abs(positionRotated.x) < halfWidth + INNER_PADDING && Math.abs(positionRotated.y) < 1.5 && Math.abs(positionRotated.z) <= halfSpacing) {
					ridingEntities.add(player.getUuid());
					final PacketByteBuf packet = PacketByteBufs.create();
					packet.writeLong(id);
					packet.writeFloat((float) (positionRotated.x / trainType.width + 0.5));
					packet.writeFloat((float) (positionRotated.z / realSpacing + 0.5) + ridingCar);
					ServerPlayNetworking.send((ServerPlayerEntity) player, PACKET_UPDATE_TRAIN_RIDING_POSITION, packet);
				}
			});
		}

		final RailwayData railwayData = RailwayData.getInstance(world);
		final Set<UUID> entitiesToRemove = new HashSet<>();
		ridingEntities.forEach(uuid -> {
			final PlayerEntity player = world.getPlayerByUuid(uuid);
			if (player != null) {
				final Vec3d positionRotated = player.getPos().subtract(carX, carY, carZ).rotateY(-carYaw).rotateX(-carPitch);
				if (player.isSpectator() || player.isSneaking() || (doorLeftOpen || doorRightOpen) && Math.abs(positionRotated.z) <= halfSpacing && (Math.abs(positionRotated.x) > halfWidth + INNER_PADDING || Math.abs(positionRotated.y) > 1.5)) {
					entitiesToRemove.add(uuid);
				}
				if (railwayData != null) {
					railwayData.updatePlayerRiding(player);
				}
			}
		});
		if (!entitiesToRemove.isEmpty()) {
			entitiesToRemove.forEach(ridingEntities::remove);
		}
	}

	@Override
	protected void handlePositions(World world, Vec3d[] positions, float ticksElapsed, float doorValueRaw, float oldDoorValue, float oldRailProgress) {
		final Box trainBox = new Box(positions[0], positions[positions.length - 1]).expand(RailwayData.RAIL_UPDATE_DISTANCE);
		world.getPlayers().forEach(player -> {
			if (trainBox.contains(player.getPos())) {
				if (!trainsInPlayerRange.containsKey(player)) {
					trainsInPlayerRange.put(player, new HashSet<>());
				}
				trainsInPlayerRange.get(player).add(this);
			}
		});
	}

	@Override
	protected void startUp(World world, int trainLength, int trainSpacing, boolean isOppositeRail) {
		canDeploy = false;
		isOnRoute = true;
		stopCounter = 0;
		speed = ACCELERATION;
		if (isOppositeRail) {
			railProgress += trainLength * trainSpacing;
			reversed = !reversed;
		}
		nextStoppingIndex = getNextStoppingIndex(trainSpacing);
	}

	@Override
	protected boolean canDeploy(Depot depot) {
		if (path.size() > 1 && depot != null) {
			depot.requestDeploy(sidingId, this);
		}
		return canDeploy;
	}

	@Override
	protected boolean isRailBlocked(int checkIndex) {
		if (trainPositions != null && checkIndex < path.size()) {
			return trainPositions.contains(path.get(checkIndex).getRailProduct());
		} else {
			return false;
		}
	}

	public boolean simulateTrain(World world, float ticksElapsed, Depot depot, Set<UUID> trainPositions, Map<PlayerEntity, Set<TrainServer>> trainsInPlayerRange, Map<Long, Set<Route.ScheduleEntry>> schedulesForPlatform) {
		this.trainPositions = trainPositions;
		this.trainsInPlayerRange = trainsInPlayerRange;
		final int oldStoppingIndex = nextStoppingIndex;
		final int oldPassengerCount = ridingEntities.size();

		simulateTrain(world, ticksElapsed, depot);

		final int offsetTicks = isOnRoute ? 0 : depot.getNextDepartureTicks(Depot.getHour(world));
		if (offsetTicks >= 0) {
			writeArrivalTimes(schedulesForPlatform, depot.routeIds, offsetTicks, trainMapping, trainLength, trainMapping.trainType.getSpacing());
		}

		return oldPassengerCount > ridingEntities.size() || oldStoppingIndex != nextStoppingIndex;
	}

	public void writeTrainPositions(Set<UUID> trainPositions) {
		if (!path.isEmpty()) {
			final int trainSpacing = trainMapping.trainType.getSpacing();
			final int headIndex = getIndex(0, trainSpacing, true);
			final int tailIndex = getIndex(trainLength, trainSpacing, false);
			for (int i = tailIndex; i <= headIndex; i++) {
				if (i > 0 && path.get(i).savedRailBaseId != sidingId) {
					trainPositions.add(path.get(i).getRailProduct());
				}
			}
		}
	}

	public void deployTrain() {
		canDeploy = true;
	}

	private int getNextStoppingIndex(int trainSpacing) {
		final int headIndex = getIndex(0, trainSpacing, false);
		for (int i = headIndex; i < path.size(); i++) {
			if (path.get(i).dwellTime > 0) {
				return i;
			}
		}
		return path.size() - 1;
	}

	private void writeArrivalTimes(Map<Long, Set<Route.ScheduleEntry>> schedulesForPlatform, List<Long> routeIds, int ticksOffset, CustomResources.TrainMapping trainMapping, int trainLength, int trainSpacing) {
		final int index = getIndex(0, trainSpacing, true);
		final Pair<Double, Double> firstTimeAndSpeed = writeArrivalTime(schedulesForPlatform, routeIds, trainMapping, trainLength, index, index == 0 ? railProgress : railProgress - distances.get(index - 1), ticksOffset, speed);

		double currentTicks = firstTimeAndSpeed.getLeft() + ticksOffset;
		double currentSpeed = firstTimeAndSpeed.getRight();
		for (int i = index + 1; i < path.size(); i++) {
			final Pair<Double, Double> timeAndSpeed = writeArrivalTime(schedulesForPlatform, routeIds, trainMapping, trainLength, i, 0, currentTicks, currentSpeed);
			currentTicks += timeAndSpeed.getLeft();
			currentSpeed = timeAndSpeed.getRight();
		}
	}

	private Pair<Double, Double> writeArrivalTime(Map<Long, Set<Route.ScheduleEntry>> schedulesForPlatform, List<Long> routeIds, CustomResources.TrainMapping trainMapping, int trainLength, int index, float progress, double currentTicks, double currentSpeed) {
		final PathData pathData = path.get(index);
		final Pair<Double, Double> timeAndSpeed = calculateTicksAndSpeed(getRailSpeed(index), pathData.rail.getLength(), progress, currentSpeed, pathData.dwellTime > 0 || index == nextStoppingIndex);

		if (pathData.dwellTime > 0) {
			final float stopTicksRemaining = Math.max(pathData.dwellTime * 10 - (index == nextStoppingIndex ? stopCounter : 0), 0);

			if (pathData.savedRailBaseId != 0) {
				final long arrivalMillis = System.currentTimeMillis() + (long) ((currentTicks + timeAndSpeed.getLeft()) * Depot.MILLIS_PER_TICK);
				final long platformId = pathData.savedRailBaseId;
				RailwayData.useRoutesAndStationsFromIndex(pathData.stopIndex - 1, routeIds, (thisRoute, nextRoute, thisStation, nextStation, lastStation) -> {
					if (lastStation != null) {
						if (!schedulesForPlatform.containsKey(platformId)) {
							schedulesForPlatform.put(platformId, new HashSet<>());
						}

						final String destinationString;
						if (thisRoute != null && thisRoute.isLightRailRoute) {
							final String lightRailRouteNumber = thisRoute.lightRailRouteNumber;
							final String[] lastStationSplit = lastStation.name.split("\\|");
							final StringBuilder destination = new StringBuilder();
							for (final String lastStationSplitPart : lastStationSplit) {
								destination.append("|").append(lightRailRouteNumber.isEmpty() ? "" : lightRailRouteNumber + " ").append(lastStationSplitPart);
							}
							destinationString = destination.length() > 0 ? destination.substring(1) : "";
						} else {
							destinationString = lastStation.name;
						}

						schedulesForPlatform.get(platformId).add(new Route.ScheduleEntry(arrivalMillis, arrivalMillis + (long) (stopTicksRemaining * Depot.MILLIS_PER_TICK), trainMapping.trainType, trainLength, platformId, destinationString, nextStation == null));
					}
				});
			}
			return new Pair<>(timeAndSpeed.getLeft() + stopTicksRemaining, timeAndSpeed.getRight());
		} else {
			return timeAndSpeed;
		}
	}

	private static Pair<Double, Double> calculateTicksAndSpeed(double railSpeed, double railLength, float progress, double initialSpeed, boolean shouldStop) {
		final double distance = railLength - progress;

		if (distance <= 0) {
			return new Pair<>(0D, initialSpeed);
		}

		if (shouldStop) {
			if (initialSpeed * initialSpeed / (2 * distance) >= ACCELERATION) {
				return new Pair<>(2 * distance / initialSpeed, 0D);
			}

			final double maxSpeed = Math.min(railSpeed, Math.sqrt(ACCELERATION * distance + initialSpeed * initialSpeed / 2));
			final double ticks = (2 * ACCELERATION * distance + initialSpeed * initialSpeed - 2 * initialSpeed * maxSpeed + 2 * maxSpeed * maxSpeed) / (2 * ACCELERATION * maxSpeed);
			return new Pair<>(ticks, 0D);
		} else {
			if (initialSpeed == railSpeed) {
				return new Pair<>(distance / initialSpeed, initialSpeed);
			} else {
				final double accelerationDistance = (railSpeed * railSpeed - initialSpeed * initialSpeed) / (2 * ACCELERATION);

				if (accelerationDistance > distance) {
					final double finalSpeed = (float) Math.sqrt(2 * ACCELERATION * distance + initialSpeed * initialSpeed);
					return new Pair<>((finalSpeed - initialSpeed) / ACCELERATION, finalSpeed);
				} else {
					final double accelerationTicks = (railSpeed - initialSpeed) / ACCELERATION;
					final double coastingTicks = (distance - accelerationDistance) / railSpeed;
					return new Pair<>(accelerationTicks + coastingTicks, railSpeed);
				}
			}
		}
	}
}