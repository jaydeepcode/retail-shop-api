package com.mangle.retailshopapp.water.model;

import java.io.Serializable;
import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlowRateCacheId implements Serializable {
    private Integer customerId;
    private String pumpType;
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FlowRateCacheId that = (FlowRateCacheId) o;
        return Objects.equals(customerId, that.customerId) &&
               Objects.equals(pumpType, that.pumpType);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(customerId, pumpType);
    }
}

