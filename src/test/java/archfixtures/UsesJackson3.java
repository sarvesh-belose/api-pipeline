package archfixtures;

import tools.jackson.databind.ObjectMapper;

/** Violates NO_JACKSON_3. */
public class UsesJackson3 {
    ObjectMapper mapper = new ObjectMapper();
}
