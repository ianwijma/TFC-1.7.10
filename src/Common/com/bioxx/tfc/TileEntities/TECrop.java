package com.bioxx.tfc.TileEntities;

import java.util.Random;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.bioxx.tfc.Core.TFC_Achievements;
import com.bioxx.tfc.Core.TFC_Climate;
import com.bioxx.tfc.Core.TFC_Core;
import com.bioxx.tfc.Core.TFC_Time;
import com.bioxx.tfc.Food.CropIndex;
import com.bioxx.tfc.Food.CropManager;
import com.bioxx.tfc.api.TFCOptions;
import com.bioxx.tfc.api.Constant.Global;

public class TECrop extends NetworkTileEntity
{
	public float growth;
	public int cropId;
	private long growthTimer;//Tracks the time since the plant was planted
	private long plantedTime;//Tracks the time when the plant was planted
	private byte sunLevel;
	public int tendingLevel;

	public TECrop()
	{
		growth = 0.1f;
		plantedTime = TFC_Time.getTotalTicks();
		growthTimer = TFC_Time.getTotalTicks();
		sunLevel = 5;
	}

	private boolean checkedSun = false;
	@Override
	public void updateEntity()
	{
		Random R = new Random();
		if(!worldObj.isRemote)
		{
			float timeMultiplier = 360 / TFC_Time.daysInYear;
			CropIndex crop = CropManager.getInstance().getCropFromId(cropId);
			long time = TFC_Time.getTotalTicks();

			if(growthTimer < time && sunLevel > 0)
			{
				sunLevel--;
				if(crop.needsSunlight && (worldObj.getBlockLightValue(xCoord, yCoord, zCoord) > 11 || worldObj.canBlockSeeTheSky(xCoord, yCoord, zCoord)))
				{
					sunLevel++;
					if(sunLevel > 30)
						sunLevel = 30;
				}

				TileEntityFarmland tef = null;
				TileEntity te = worldObj.getTileEntity(xCoord, yCoord - 1, zCoord);
				if(te != null && te instanceof TileEntityFarmland)
					tef = (TileEntityFarmland) te;

				float ambientTemp = TFC_Climate.getHeightAdjustedTempSpecificDay(TFC_Time.getDayOfYearFromTick(growthTimer), xCoord, yCoord, zCoord);
				float tempAdded = 0;
				boolean isDormant = false;

				if(!crop.dormantInFrost && ambientTemp < crop.minGrowthTemp)
					tempAdded = -0.03f * (crop.minGrowthTemp - ambientTemp);
				else if(crop.dormantInFrost && ambientTemp < crop.minGrowthTemp)
				{
					if(growth > 1)
						tempAdded = -0.03f * (crop.minGrowthTemp - ambientTemp);
					isDormant = true;
				}
				else if(ambientTemp < 28)
					tempAdded = ambientTemp * 0.00035f;
				else if(ambientTemp < 37)
					tempAdded = (28 - (ambientTemp-28)) * 0.0003f;

				if(!crop.dormantInFrost && ambientTemp < crop.minAliveTemp)
				{
					killCrop(crop);
				}
				else if(crop.dormantInFrost && ambientTemp < crop.minAliveTemp)
				{
					if(growth > 1)
					{
						killCrop(crop);
					}
				}

				int nutriType = crop.cycleType;
				int nutri = tef != null ? tef.nutrients[nutriType] : 18000;
				int fert = tef != null ? tef.nutrients[3] : 0;
				int soilMax = tef != null ? tef.getSoilMax() : 18000;
				//waterBoost only helps if you are playing on a longer than default year length.
				float waterBoost = com.bioxx.tfc.Blocks.BlockFarmland.isFreshWaterNearby(worldObj, xCoord, yCoord, zCoord) ? 0.1f : 0;

				//Allow the fertilizer to make up for lost nutrients
				nutri = Math.min(nutri + fert, (int)(soilMax * 1.25f));

				float nutriMult = (0.2f + ((float)nutri/(float)soilMax) * 0.5f) + waterBoost;

				if(tef != null && !isDormant)
				{
					if(tef.nutrients[nutriType] > 0)
						tef.DrainNutrients(nutriType, crop.nutrientUsageMult);
					//Drain Fertilizer
					if(tef.nutrients[3] > 0)
						tef.DrainNutrients(3, crop.nutrientUsageMult);
				}

				float growthRate = (((crop.numGrowthStages / (crop.growthTime * TFC_Time.timeRatio96)) + tempAdded) * nutriMult) * timeMultiplier;

				int oldGrowth = (int) Math.floor(growth);

				if(!isDormant)
					growth += growthRate;

				if(oldGrowth < (int) Math.floor(growth))
					worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
				this.broadcastPacketInRange();

				if((TFCOptions.enableCropsDie && (crop.maxLifespan == -1 && growth > crop.numGrowthStages + ((float)crop.numGrowthStages / 2))) || growth < 0)
				{
					worldObj.setBlockToAir(xCoord, yCoord, zCoord);
				}

				growthTimer += (R.nextInt(2) + 23) * TFC_Time.hourLength;
			}
			else if(crop.needsSunlight && sunLevel <= 0)
			{
				killCrop(crop);
			}

			if(worldObj.isRaining() && TFC_Climate.getHeightAdjustedTemp(xCoord, yCoord, zCoord) < 0)
			{
				if(!crop.dormantInFrost || growth > 1)
				{
					killCrop(crop);
				}
			}
		}
	}

	public float getEstimatedGrowth(CropIndex crop)
	{
		return ((float)crop.numGrowthStages / (growthTimer - plantedTime / TFC_Time.dayLength)) * 1.5f;
	}

	public void onHarvest(World world, EntityPlayer player, boolean isBreaking)
	{
		if(!world.isRemote)
		{
			CropIndex crop = CropManager.getInstance().getCropFromId(cropId);
			if(crop != null && growth >= crop.numGrowthStages - 1)
			{
				ItemStack is1 = crop.getOutput1(this);
				ItemStack is2 = crop.getOutput2(this);

				if(is1 != null)
					world.spawnEntityInWorld(new EntityItem(world, xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, is1));

				if(is2 != null)
					world.spawnEntityInWorld(new EntityItem(world, xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, is2));

				ItemStack seedStack = crop.getSeed();
				int skill = 20 - (int)(20 * TFC_Core.getSkillStats(player).getSkillMultiplier(Global.SKILL_AGRICULTURE));
				seedStack.stackSize = 1 + (world.rand.nextInt(1 + skill) == 0 ? 1 : 0);
				if(seedStack != null && isBreaking)
					world.spawnEntityInWorld(new EntityItem(world, xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, seedStack));

				TFC_Core.getSkillStats(player).increaseSkill(Global.SKILL_AGRICULTURE, 1);

				if(TFC_Core.isSoil(world.getBlock(xCoord, yCoord - 1, zCoord)))
					player.addStat(TFC_Achievements.achWildVegetable, 1);
			}
			else if (crop != null)
			{
				ItemStack is = crop.getSeed();
				is.stackSize = 1;
				if(is != null)
					world.spawnEntityInWorld(new EntityItem(world, xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, is));
			}
		}
	}

	public void killCrop(CropIndex crop)
	{
		ItemStack is = crop.getSeed();
		is.stackSize = 1;
		if (is != null)
		{
			worldObj.spawnEntityInWorld(new EntityItem(worldObj, xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, is));
			worldObj.setBlockToAir(xCoord, yCoord, zCoord);
		}
	}

	/**
	 * Reads a tile entity from NBT.
	 */
	@Override
	public void readFromNBT(NBTTagCompound nbt)
	{
		super.readFromNBT(nbt);
		growth = nbt.getFloat("growth");
		cropId = nbt.getInteger("cropId");
		growthTimer = nbt.getLong("growthTimer");
		plantedTime = nbt.getLong("plantedTime");
	}

	/**
	 * Writes a tile entity to NBT.
	 */
	@Override
	public void writeToNBT(NBTTagCompound nbt)
	{
		super.writeToNBT(nbt);
		nbt.setFloat("growth", growth);
		nbt.setInteger("cropId", cropId);
		nbt.setLong("growthTimer", growthTimer);
		nbt.setLong("plantedTime", plantedTime);
	}

	@Override
	public void handleInitPacket(NBTTagCompound nbt) {
		readFromNBT(nbt);
		worldObj.markBlockRangeForRenderUpdate(xCoord, yCoord, zCoord, xCoord, yCoord, zCoord);
	}

	@Override
	public void handleDataPacket(NBTTagCompound nbt) 
	{
		if(worldObj.isRemote)
		{
			growth = nbt.getFloat("growth");
			worldObj.markBlockRangeForRenderUpdate(xCoord, yCoord, zCoord, xCoord, yCoord, zCoord);
		}
	}

	@Override
	public void createDataNBT(NBTTagCompound nbt) {
		nbt.setFloat("growth", growth);
	}

	@Override
	public void createInitNBT(NBTTagCompound nbt) {
		nbt.setFloat("growth", growth);
		nbt.setInteger("cropId", cropId);
	}

}