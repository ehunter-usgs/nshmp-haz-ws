package gov.usgs.earthquake.nshm.www.meta;

/**
 * Interface implemented by enum parameters that impose restrictions on other
 * parameter choices.
 */
@SuppressWarnings("javadoc")
public interface Constrained {
  public Constraints constraints();
}
