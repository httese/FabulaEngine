package com.macbury.fabula.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Stack;

import org.simpleframework.xml.core.Commit;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.materials.Material;
import com.badlogic.gdx.graphics.g3d.materials.TextureAttribute;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Disposable;
import com.macbury.fabula.manager.G;
import com.macbury.fabula.terrain.foliage.Foliage;
import com.macbury.fabula.terrain.foliage.FoliageRenderable;
import com.macbury.fabula.terrain.foliage.FoliageSet;
import com.macbury.fabula.terrain.tile.Tile;
import com.macbury.fabula.terrain.tileset.Tileset;
import com.macbury.fabula.terrain.water.Water;
import com.macbury.fabula.terrain.water.WaterRenderable;

public class Terrain implements Disposable {
  private static final String TAG = "Terrain";
  private Sector[][] sectors;
  private Tile[][] tiles;
  
  private int columns;
  private int rows;
  private String tilesetName;
  
  private int horizontalSectorCount;
  private int veriticalSectorCount;
  private int totalSectorCount;
  private int visibleSectorCount;
  
  private Stack<Sector> visibleSectors;
  private ArrayList<Sector> rebuildSectorsArray = new ArrayList<Sector>();
  private Vector3 intersection = new Vector3();
  private boolean debug = false;
  
  private FoliageSet foliageSet;
  private Tileset tileset;
  private TerrainDebugListener debugListener;
  private Material terrainMaterial;
  private TerrainShader terrainShader;
  
  public Terrain(int columns, int rows) {
    this.columns      = columns;
    this.rows         = rows;
    
    Tile.GID_COUNTER  = 1;
    this.tiles        = new Tile[columns][rows];
    if (columns % Sector.COLUMN_COUNT != 0 || rows%Sector.ROW_COUNT != 0) {
      throw new RuntimeException("Map size must be proper!");
    }
    
    this.terrainShader   = new TerrainShader();
  }
  
  public void setTileset(String name) {
    tilesetName          = name;
    tileset              = G.db.getTileset(tilesetName);
    this.terrainMaterial = new Material(TextureAttribute.createDiffuse(tileset.getTexture()));
    this.terrainShader.setMaterial(terrainMaterial);
    
    clearSectorRenderData();
  }
  
  private void clearSectorRenderData() {
    for (int x = 0; x < horizontalSectorCount; x++) {
      for (int z = 0; z < veriticalSectorCount; z++) {
        Sector sector = this.sectors[x][z];
        if (sector != null) {
          sector.cleanRenderableData();
        }
      }
    }
  }

  public void buildSectors() {
    this.horizontalSectorCount = columns/Sector.COLUMN_COUNT;
    this.veriticalSectorCount  = rows/Sector.ROW_COUNT;
    this.totalSectorCount      = horizontalSectorCount * veriticalSectorCount;
    
    this.sectors               = new Sector[horizontalSectorCount][veriticalSectorCount];
    this.visibleSectors        = new Stack<Sector>();
    
    for (int x = 0; x < horizontalSectorCount; x++) {
      for (int z = 0; z < veriticalSectorCount; z++) {
        Sector sector = new Sector(new Vector3(x * Sector.COLUMN_COUNT, 0, z * Sector.ROW_COUNT), this);
        sector.build();
        this.sectors[x][z] = sector;
      }
    }
    
    System.gc();
  }
  
  public void fillEmptyTilesWithDebugTile() {
    for (int z = 0; z < rows; z++) {
      for (int x = 0; x < columns; x++) {
        if (!haveTile(x,z)) {
          Tile tile = new Tile(x, 0, z);
          tile.setAutoTile(tileset.getDefaultAutoTile());
          setTile(x, z, tile);
        }
      }
    }
  }

  private boolean haveTile(int x, int z) {
    return getTile(x,z) != null;
  }

  public Tile getTile(int x, int z) {
    try {
      return this.tiles[x][z];
    } catch (ArrayIndexOutOfBoundsException e) {
      return null;
    }
  }
  
  public void setTile(int x, int z, Tile tile) {
    tile.setX(x);
    tile.setZ(z);
    this.tiles[x][z] = tile;
  }
  
  public void setTile(float x, float z, Tile tile) {
    setTile((int)x, (int)z, tile);
  }
  
  public void renderTerrainGeometry(Camera camera, ModelBatch batch) {
    visibleSectors.clear();
    terrainShader.setDebugListener(debugListener);
    visibleSectorCount  = 0;
    
    for (int x = 0; x < horizontalSectorCount; x++) {
      for (int z = 0; z < veriticalSectorCount; z++) {
        Sector sector                = this.sectors[x][z]; 
        TerrainRenderable renderable = sector.getTerrainRenderable(terrainShader);
        if (sector.visibleInCamera(camera)) {
          batch.render(renderable);

          visibleSectors.add(sector);
          visibleSectorCount++;
        }
      }
    }
  }
  
  public void renderLiquidGeometry(ModelBatch batch, Water water) {
    for (Sector sector : visibleSectors) {
      WaterRenderable wr = sector.getWaterRenderable(water);
      if (wr != null) {
        batch.render(wr);
      }
    }
  }
  
  public void renderFoliageGeometry(ModelBatch modelBatch, Foliage foliage) {
    for (Sector sector : visibleSectors) {
      FoliageRenderable renderable = sector.getFoliageRenderable(foliage);
      if (renderable != null) {
        modelBatch.render(renderable);
      }
    }
  }

  public int getTotalSectorCount() {
    return totalSectorCount;
  }

  public int getVisibleSectorCount() {
    return this.visibleSectorCount;
  }

  public Vector3 getPositionForRay(Ray ray, Vector3 mouseTilePosition) {
    Vector3 intersectedVector = null;
    for (Sector sector : visibleSectors) {
      intersectedVector = sector.getPositionForRay(ray, mouseTilePosition);
      
      if (intersectedVector != null) {
        break;
      }
    }
    return intersectedVector;
  }
  
  public void buildTerrainUsingImageHeightMap(String pathToHeightMap) {
    FileHandle file       = Gdx.files.local(pathToHeightMap);
    Pixmap heightmapImage = new Pixmap(file);
    Color color           = new Color();
    
    for (int z = 0; z < rows; z++) {
      for (int x = 0; x < columns; x++) {
        Color.rgba8888ToColor(color, heightmapImage.getPixel(x, z));
        setTile(x, z, new Tile(x, color.r*30, z));
      }
    }
  }

  public Vector3 getSnappedPositionForRay(Ray ray, Vector3 mouseTilePosition) {
    Vector3 pos = getPositionForRay(ray, mouseTilePosition);
    if (pos != null) {
      Tile tile   = getTile(pos);
      
      float y = Math.max(0, pos.y);
      if (tile != null) {
        y = tile.getY();
      }
      pos.set((float)Math.floor(pos.x), y, (float)Math.floor(pos.z));
    }
    
    return pos;
  }

  public Tile getTile(Vector3 pos) {
    return getTile((int)pos.x, (int)pos.z);
  }
  
  public Sector getSectorForTile(Tile tile) {
    int x = (int) Math.floor(tile.getX() / Sector.COLUMN_COUNT);
    int z = (int) Math.floor(tile.getZ() / Sector.ROW_COUNT);
    return sectors[x][z];
  }
  
  public void addSectorToRebuildFromTile(Tile tile) {
    Sector sector = getSectorForTile(tile);
    if (sector != null && this.rebuildSectorsArray.indexOf(sector) == -1) {
      this.rebuildSectorsArray.add(sector);
    }
  }
  
  public void rebuildUsedSectors() {
    Gdx.app.log(TAG, "Sectors to rebuild: " + rebuildSectorsArray.size());
    for (Sector sector : rebuildSectorsArray) {
      sector.build();
    }
    rebuildSectorsArray.clear();
  }
  
  public boolean isDebuging() {
    return debug;
  }
  

  public int getTileIdByPos(Vector3 pos) {
    return (int)((pos.z - 1) * this.columns + pos.x);
  }
  
  public TerrainDebugListener getDebugListener() {
    return debugListener;
  }
  
  public boolean isDebug() {
    return debugListener != null;
  }

  public void setDebugListener(TerrainDebugListener debugListener) {
    this.debugListener   = debugListener;
    this.debug           = true;
    clearSectorRenderData();
  }

  public interface TerrainDebugListener {
    public void onDebugTerrainConfigureShader(ShaderProgram shader);
  }

  public Tileset getTileset() {
    return this.tileset;
  }
  
  public Tile[][] getTiles() {
    return tiles;
  }

  public int getColumns() {
    return columns;
  }

  public int getRows() {
    return rows;
  }

  @Override
  public void dispose() {
    for (int x = 0; x < horizontalSectorCount; x++) {
      for (int z = 0; z < veriticalSectorCount; z++) {
        Sector sector = this.sectors[x][z]; 
        sector.dispose();
      }
    }
  }

  public Tile getTileByTilePosition(Tile t) {
    return getTile(t.getX(), t.getZ());
  }

  private Tile getTile(float x, float z) {
    return getTile((int)x, (int)z);
  }
  
  @Commit
  public void commit() {
     //decoded = encoder.decode(encoded, format);
     //encoded = null;
  }

  public boolean isVisibleTile(int x, int z) {
    for (Sector sector : visibleSectors) {
      if (sector.containsTilePosition(x, z)){
        return true;
      }
    }
    
    return false;
  }
  
  public boolean isVisible(Vector3 vector) {
    for (Sector sector : visibleSectors) {
      if (sector.getBounds().contains(vector)) {
        return true;
      }
    }
    return false;
  }

  public boolean isVisible(BoundingBox box) {
    for (Sector sector : visibleSectors) {
      if (sector.getBounds().contains(box)) {
        return true;
      }
    }
    return false;
  }

  public boolean resize(int width, int height) {
    if (width < columns || height < rows) {
      return false;
    }
    
    Tile[][] tempTiles = new Tile[width][height];
    
    for (int x = 0; x < width; x++) {
      try {
        tempTiles[x] = Arrays.copyOf(tiles[x], height);
      } catch (ArrayIndexOutOfBoundsException e) {
        tempTiles[x] = new Tile[height];
      }
      
    }
    
    this.tiles = tempTiles;
    
    rows    = height;
    columns = width;
    fillEmptyTilesWithDebugTile();
    buildSectors();
    
    return true;
  }

  public Stack<Sector> getVisibleSectors() {
    return visibleSectors;
  }

  public FoliageSet getFoliageSet() {
    return foliageSet;
  }

  public void setFoliageSet(String name) {
    this.foliageSet = G.db.getFoliageSet(name);
  }

  
}
