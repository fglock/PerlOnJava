# Benchmarks

### Performance Benchmarks

These benchmarks provide an order-of-magnitude comparison with Perl:

| Version | Feature | Performance Relative to Perl |
|---------|---------|------------------------------|
| v1.0.0  | Lexer and Parser | 50k lines/second (N/A) |
| v1.1.0  | Numeric operations | 2x faster |
|         | String operations | Comparable |
|         | Eval-string | 10x slower |
| v1.5.0  | Example: `life.pl` | 3x slower |
| v1.6.0  | Module compilation | 5x slower |
| v1.7.0  | Module compilation | 5x slower  |
|         | Example: `life.pl` | Comparable |
|         | Eval-string        | 7x slower  |

Notes:
- v1.2.0 through v1.4.0: No significant performance changes.
- Script `life.pl` run without `sleep` between iterations.
- Module compilation benchmark: Repeatedly loading `Data::Dumper`:

  ```perl
  perl -Ilib -e 'for (1..80) { eval "use Data::Dumper;"; delete $INC{"Data/Dumper.pm"}; }'
  ```

