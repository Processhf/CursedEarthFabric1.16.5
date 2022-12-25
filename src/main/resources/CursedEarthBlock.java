package tfar.cursedearth;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.GrassBlock;
import net.minecraft.entity.*;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.BaseText;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.BlockView;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.SpawnSettings;
import net.minecraft.world.level.*;
import net.minecraft.world.gen.StructureAccessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tfar.cursedearth.mixin.ServerWorldAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;

public class CursedEarthBlock extends GrassBlock {
	public CursedEarthBlock(Settings properties) {
		super(properties);
	}

	private static final Logger logger = LogManager.getLogger();

	@Override
	public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean isMoving) {
		world.getBlockTickScheduler().schedule(pos, state.getBlock(), world.random.nextInt(125));
	}

	@Override
	public ActionResult onUse(BlockState p_225533_1_, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult p_225533_6_) {
		if (player.getStackInHand(hand).isEmpty() && player.isSneaking() && !world.isClient && hand == Hand.MAIN_HAND) {

			ServerChunkManager s = (ServerChunkManager) world.getChunkManager();

			List<SpawnDetail> spawnInfo = new ArrayList<>();

			BlockPos up = pos.up();
			StructureAccessor structureAccessor = ((ServerWorld) world).getStructureAccessor();
			List<SpawnSettings.SpawnEntry> entries = s.getChunkGenerator().getEntitySpawnList(world.getBiome(pos), structureAccessor, SpawnGroup.MONSTER, up);
			// nothing can spawn, occurs in places such as mushroom biomes
			if (entries.size() == 0) {
				player.sendMessage(new TranslatableText("text.cursedearth.nospawns"), false);
				return ActionResult.SUCCESS;
			} else {
				for (SpawnSettings.SpawnEntry entry : entries) {
					spawnInfo.add(new SpawnDetail(entry));
				}
				BaseText names1 = new TranslatableText("Names: ");
				for (SpawnDetail detail : spawnInfo) {
					names1.append(new TranslatableText(detail.displayName)).append(new LiteralText(", "));
				}
				player.sendMessage(names1, false);
			}
			return ActionResult.SUCCESS;
		}
		return ActionResult.FAIL;
	}

	public static class SpawnDetail {

		private final String displayName;

		//    private boolean lightEnabled = true;
		public SpawnDetail(SpawnSettings.SpawnEntry entry) {
			displayName = entry.type.getTranslationKey().replace("Entity", "");
		}
	}

	protected final Predicate<Entity> p = a -> true;

	@Override
	public void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		if (isInDaylight(world, pos)/*diesInSunlight.get()*/) {
			world.setBlockState(pos, Blocks.DIRT.getDefaultState());
		} else {
			/*naturallySpreads.get()*/
			if (world.getLightLevel(pos.up()) <= 7 && world.getBlockState(pos.up()).isAir()) {
				BlockState blockstate = this.getDefaultState();
				for (int i = 0; i < 4; ++i) {
					BlockPos pos1 = pos.add(random.nextInt(3) - 1, random.nextInt(5) - 3, random.nextInt(3) - 1);
					if (world.getBlockState(pos1).getBlock().isIn(CursedEarth.spreadable) && world.getBlockState(pos1.up()).isAir()) {
						world.setBlockState(pos1, blockstate.with(SNOWY, world.getBlockState(pos1.up()).getBlock() == Blocks.SNOW));
					}
				}
			}
		}
	}

	@Override
	public void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		world.getBlockTickScheduler().schedule(pos, state.getBlock(), random.nextInt(125));//todo config
		//dont spawn in water
		if (!world.getFluidState(pos.up()).isEmpty()) {
			log("there appears to be water blocking the spawns");
			return;
		}
		//mobcap used because mobs are laggy in large numbers todo: how well does this work on servers. answer: it doesn't, implement a tag checking for cursed earth mobs
		long mobcount = ((ServerWorldAccessor) world).entitiesById().values().stream().filter(Monster.class::isInstance).count();
		if (mobcount > 500) {
			log("there are too many mobs in world to spawn more");
			return;
		}
		int r = 1;//spawnRadius.get();
		if (world.getEntitiesByType(EntityType.PLAYER, new Box(-r, -r, -r, r, r, r), p).size() > 0) return;
		findMonsterToSpawn(world, pos, random).ifPresent(mob -> {
			mob.setPos(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5);
			if (!world.intersectsEntities(mob, world.getBlockState(pos.up()).getCollisionShape(world, pos))) {
				log("colliding blocks or entities preventing spawn");
				return;
			}
			log("spawn successful");
			world.spawnEntity(mob);
		});
	}

	public static void log(String message) {
		//logger.info(message);
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		world.getBlockTickScheduler().schedule(pos, state.getBlock(), world.random.nextInt(125));
	}

	@Override
	public boolean canGrow(World world, Random random, BlockPos pos, BlockState state) {
		return false;//no
	}

	@Override
	public boolean isFertilizable(BlockView world, BlockPos pos, BlockState state, boolean isClient) {
		return false;//no
	}

	public boolean isInDaylight(World world, BlockPos pos) {
		return world.isDay() && world.getBrightness(pos.up()) > 0.5F && world.isSkyVisible(pos.up());
	}

	private Optional<MobEntity> findMonsterToSpawn(ServerWorld world, BlockPos pos, Random rand) {
		//required to account for structure based mobs such as wither skeletons
		ServerChunkManager s = world.getChunkManager();
		StructureAccessor structureAccessor = world.getStructureAccessor();
		List<SpawnSettings.SpawnEntry> spawnOptions = s.getChunkGenerator().getEntitySpawnList(world.getBiome(pos), structureAccessor, SpawnGroup.MONSTER, pos);
		//there is nothing to spawn
		if (spawnOptions.size() == 0) {
			return Optional.empty();
		}
		int found = rand.nextInt(spawnOptions.size());
		SpawnSettings.SpawnEntry entry = spawnOptions.get(found);
		//can the mob actually spawn here naturally, filters out mobs such as slimes which have more specific spawn requirements but
		// still show up in spawnlist; ignore them when force spawning
		if (!SpawnRestriction.canSpawn(entry.type, world, SpawnReason.NATURAL, pos.up(), world.random)/*!forceSpawn.get()*/) {
			log("entity " + entry.type.toString() + " cannot naturally spawn here");
			return Optional.empty();
		} else if (CursedEarth.blacklisted_entities.contains(entry.type)) {
			return Optional.empty();
		}
		EntityType<?> type = entry.type;
		Entity ent = type.create(world);
		//cursed earth only works with hostiles
		((MobEntity) ent).initialize(world, world.getLocalDifficulty(pos), SpawnReason.NATURAL, null, null);
		return Optional.of((MobEntity) ent);
	}
}