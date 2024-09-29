import { StyleSheet, Text, View } from 'react-native';

import * as ExpoBlePeripheral from 'expo-ble-peripheral';

export default function App() {
  return (
    <View style={styles.container}>
      <Text>{ExpoBlePeripheral.hello()}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
    alignItems: 'center',
    justifyContent: 'center',
  },
});
