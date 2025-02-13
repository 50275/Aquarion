package aquarion.world.blocks.production;

import aquarion.AquaAttributes;
import aquarion.blocks.AquaDefense;
import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.math.geom.Vec2;
import arc.struct.ObjectMap;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.gen.Building;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.graphics.Shaders;
import mindustry.type.ItemStack;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.payloads.BlockProducer;
import mindustry.world.blocks.payloads.BuildPayload;
import arc.func.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.world.blocks.payloads.Payload;
import mindustry.world.blocks.payloads.PayloadBlock;
import mindustry.world.meta.*;

import static aquarion.AquaAttributes.bauxite;
import static aquarion.blocks.AquaDefense.bauxiteWall;
import static aquarion.blocks.AquaDefense.galliumWall;
import static mindustry.Vars.*;

import static mindustry.Vars.tilesize;

//Basically a wallCrafter drill shit-thing that makes payload blocks of what it is mining
public class WallPayloadDrill extends PayloadBlock {
    public ObjectMap<Attribute, Block> attributeBlockMap = new ObjectMap<>();
    public float buildSpeed = 0.4f;
    public TextureRegion side1, side2;

    public void load(Block block){
        super.load();
        side1 = Core.atlas.find(block.name + "-side1");
        side2 = Core.atlas.find(block.name + "-side2");
    }

    public WallPayloadDrill(String name) {
        super(name);
        size = 3;
        update = true;
        outputsPayload = true;
        solid = true;
        rotate = true;
        regionRotated1 = 1;
    }

    @Override
    public void setBars() {
        super.setBars();
        addBar("progress", (WallPayloadDrillBuild entity) ->
                new Bar("bar.progress", Pal.ammo, () -> entity.recipe() == null ? 0f : entity.progress / entity.recipe().buildCost));
    }

    /**
     * Calculate the efficiency based on the surrounding blocks' attributes and store dominant attribute for recipe selection.
     */
    public float calculateEfficiency(int tx, int ty, int rotation, ObjectMap<Attribute, Float> attributeTotals) {
        float efficiency = 0f;
        int cornerX = tx - (size - 1) / 2, cornerY = ty - (size - 1) / 2;

        for (int i = 0; i < size; i++) {
            int rx = 0, ry = 0;

            switch (rotation) {
                case 0 -> {
                    rx = cornerX + size;
                    ry = cornerY + i;
                }
                case 1 -> {
                    rx = cornerX + i;
                    ry = cornerY + size;
                }
                case 2 -> {
                    rx = cornerX - 1;
                    ry = cornerY + i;
                }
                case 3 -> {
                    rx = cornerX + i;
                    ry = cornerY - 1;
                }
            }

            Tile other = world.tile(rx, ry);
            if (other != null && other.solid()) {
                for (Attribute attribute : attributeBlockMap.keys()) {
                    float value = other.block().attributes.get(attribute);
                    if (value > 0) {
                        efficiency += value;
                        attributeTotals.put(attribute, attributeTotals.get(attribute, 0f) + value);
                    }
                }
            }
        }
        return efficiency;
    }

    public class WallPayloadDrillBuild extends PayloadBlockBuild<BuildPayload> {
        public float progress = 0f;
        public float heat = 0f;
        public float time = 0f;

        /**
         * Determines the recipe based on the dominant attribute in the surrounding blocks.
         */
        public Block recipe() {
            ObjectMap<Attribute, Float> attributeTotals = new ObjectMap<>();
            calculateEfficiency(tileX(), tileY(), rotation, attributeTotals);

            Attribute dominantAttribute = null;
            float highestTotal = 0;

            for (ObjectMap.Entry<Attribute, Float> entry : attributeTotals) {
                if (entry.value > highestTotal) {
                    highestTotal = entry.value;
                    dominantAttribute = entry.key;
                }
            }

            return dominantAttribute != null ? attributeBlockMap.get(dominantAttribute) : null;
        }
        @Override
        public void updateTile() {

                if (payload != null) {
                    Building backBlock = nearby(Geometry.d4((rotation + 2) % 4).pack());

                    if (backBlock instanceof PayloadBlockBuild<?> pb && pb.payload == null) {
                        pb.handlePayload(this, payload);
                        payload = null;
                }
            }
            super.updateTile();
            Block recipe = recipe();
            if (recipe == null) return;

            ObjectMap<Attribute, Float> attributeTotals = new ObjectMap<>();
            float efficiency = calculateEfficiency(tileX(), tileY(), rotation, attributeTotals);
            float effectiveBuildSpeed = buildSpeed * efficiency;
            progress += effectiveBuildSpeed * edelta();
            boolean produce = recipe != null && efficiency > 0 && payload == null;
            if(produce) {
                if (progress >= recipe.buildCost) {
                    payload = new BuildPayload(recipe, team);
                    recipe.placeEffect.at(x, y, recipe.size / tilesize);
                    payVector.setZero();
                    progress %= recipe.buildCost;
                }
            }
            heat = Mathf.lerpDelta(heat, 1f, 0.15f);
            time += heat * delta();
            moveOutPayload();
        }

        @Override
        public void draw() {
            if (region != null) {
                Draw.rect(region, x, y);
            }
            if (outRegion != null) {
                Draw.rect(outRegion, x, y, rotdeg());
            }

            var recipe = recipe();
            if (recipe != null) {
                Drawf.shadow(x, y, recipe.size * tilesize * 2f, progress / recipe.buildCost);
                Draw.draw(Layer.blockBuilding, () -> {
                    Draw.color(Pal.accent);

                    for (TextureRegion region : recipe.getGeneratedIcons()) {
                        if (region != null) {
                            Shaders.blockbuild.region = region;
                            Shaders.blockbuild.time = time;
                            Shaders.blockbuild.progress = progress / recipe.buildCost;

                            Draw.rect(region, x, y, recipe.rotate ? rotdeg() : 0);
                            Draw.flush();
                        }
                    }

                    Draw.color();
                });
                Draw.z(Layer.blockBuilding + 1);
                Draw.color(Pal.accent, heat);

                Lines.lineAngleCenter(x + Mathf.sin(time, 10f, Vars.tilesize / 2f * recipe.size + 1f), y, 90, recipe.size * Vars.tilesize + 1f);

                Draw.reset();
            }

            if (payload != null) {
                drawPayload();
            }

            if (topRegion != null) {
                Draw.z(Layer.blockBuilding + 1.1f);
                Draw.rect(topRegion, x, y);
            }

            if ((this.rotation > 1 ? side2 : side1) != null) {
                Draw.z(Layer.blockBuilding + 1.1f);
                Draw.rect(this.rotation > 1 ? side2 : side1, this.x, this.y, this.rotdeg());
            }
        }
            @Override
        public void dumpPayload(){
            float tx = Angles.trnsx(payload.rotation(), 0.1f), ty = Angles.trnsy(payload.rotation(), 0.1f);
            payload.set(payload.x() + tx, payload.y() + ty, payload.rotation());

            if(payload.dump()){
                payload = null;
            }else{
                payload.set(payload.x() - tx, payload.y() - ty, payload.rotation());
            }
        }

        public boolean hasArrived(){
            return payVector.isZero(0.01f);
        }
        @Override
        public void moveOutPayload(){
            if(payload == null) return;

            updatePayload();

            Vec2 dest = Tmp.v1.trns(rotdeg() + 180, size * tilesize/2f);

            payRotation = Angles.moveToward(payRotation , rotdeg() + 180, payloadRotateSpeed * delta());
            payVector.approach(dest, payloadSpeed * delta());

            Building front = front();
            boolean canDump = front == null || !front.tile().solid();
            boolean canMove = front != null && (front.block.outputsPayload || front.block.acceptsPayload);

            if(canDump && !canMove){
                pushOutput(payload, 1f - (payVector.dst(dest) / (size * tilesize / 2f)));
            }

            if(payVector.within(dest, 0.001f)){
                payVector.clamp(-size * tilesize / 2f, -size * tilesize / 2f, size * tilesize / 2f, size * tilesize / 2f);

                if(canMove){
                    if(movePayload(payload)){
                        payload = null;
                    }
                }else if(canDump){
                    dumpPayload();
                }
            }
        }
    }
}
