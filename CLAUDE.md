# Reglas para Claude en este proyecto

1. En respuestas y explicaciones, usa fragmentos o diffs en vez de pegar archivos completos cuando el cambio sea pequeño.
2. Cuando una tarea sea simple (ajustes menores, cambios de texto, correcciones pequeñas) y probablemente no requiera el modelo más potente, avisa al usuario para que pueda cambiar de modelo con `/model`.
3. No uses herramientas de servidores MCP que no sean necesarias para la tarea actual.
4. Cuando le des al usuario un mensaje, comando o texto que tenga que copiar y pegar (para ti mismo en otra sesión, para la terminal, o para cualquier otro lado), ponlo siempre dentro de un bloque de código, para que aparezca el botón de copiar. No uses bloques de código para el resto de la respuesta, solo para lo que el usuario necesite copiar.
5. Sigue cuidando el uso del límite: respuestas cortas, sin repetir código completo si un fragmento basta, y sin usar herramientas MCP que no necesites para la tarea actual.
