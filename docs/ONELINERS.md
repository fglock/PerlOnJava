# Useful One-Liners

## YAML Module

Test YAML dump:
```bash
./jperl -MYAML -e 'print Dump({ hello => "world", numbers => [1,2,3] })'
```

Test YAML round-trip:
```bash
./jperl -MYAML -e 'my $data = { hello => "world", numbers => [1,2,3] }; print Dump(Load(Dump($data)))'
```

## JSON Module

Test JSON encode:
```bash
./jperl -MJSON -e 'print encode_json({ hello => "world", numbers => [1,2,3] })'
```

Test JSON pretty print:
```bash
./jperl -MJSON -e 'my $json = JSON->new->pretty(1); print $json->encode({ hello => "world", numbers => [1,2,3] })'
```

## File Operations

Write and read YAML file:
```bash
./jperl -MYAML -e 'DumpFile("test.yml", { hello => "world" }); print Dump(LoadFile("test.yml"))'
```

Write and read JSON file:
```bash
./jperl -MJSON -e 'my $json = JSON->new; DumpFile("test.json", { hello => "world" }); print encode_json(from_json(LoadFile("test.json")))'
```
