package tz.go.tirdo.cbrms.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Named rounding rules approved by Finance (FR06). Values match tariff_version.rounding_rule. */
public enum RoundingRule {
    /** Round half up to two decimal places. */
    HALF_UP_2 {
        @Override
        public BigDecimal apply(BigDecimal value) {
            return value.setScale(2, RoundingMode.HALF_UP);
        }
    },
    /** Round half up to whole shillings, stored with two decimal places. */
    WHOLE_SHILLING_HALF_UP {
        @Override
        public BigDecimal apply(BigDecimal value) {
            return value.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
        }
    };

    public abstract BigDecimal apply(BigDecimal value);
}
