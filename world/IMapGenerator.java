package world;

public interface IMapGenerator {
    int[][] generateMaze(int mapSize, int startX, int startY, int exitAdjX, int exitAdjY);
}