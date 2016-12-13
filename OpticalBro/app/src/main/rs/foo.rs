#pragma version(1)
#pragma rs java_package_name(edu.mit.web.opticalbro)

float stuff[];

float __attribute__((kernel)) Adjust(float thisAvg, float otherAvg, int E_this, int E_other, int E_t, uint32_t x, uint32_t y) {
    float lambda = 5.0;
    float adjustment = (E_this * thisAvg + E_other * otherAvg + E_t) / (1 + lambda * (E_this*E_this + E_other*E_other));
    float velocity = thisAvg - E_this * adjustment;
    return velocity;
}

rs_allocation velocities_in;
int width;
int height;

float __attribute__((kernel)) CalculateAverages(uint32_t x, uint32_t y) {
    int neighborCount = 0;
    float neighborSum = 0;
    // For each neighbor, check if it's possible for that neighbor to exist
    if (x > 0) {
        neighborCount++;
        neighborSum += rsGetElementAt_float(velocities_in, x-1, y);
    }
    if (y > 0) {
        neighborCount++;
        neighborSum += rsGetElementAt_float(velocities_in, x, y-1);
    }
    if (x < width - 1) {
        neighborCount++;
        neighborSum += rsGetElementAt_float(velocities_in, x+1, y);
    }
    if (y < height - 1) {
        neighborCount++;
        neighborSum += rsGetElementAt_float(velocities_in, x, y+1);
    }
    return (neighborSum / (float)neighborCount);
}

int root() {
}

void init() {

}