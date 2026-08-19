# Unicode 17.0 generator inputs

This directory contains the minimal Unicode source snapshot needed to
reproduce PerlOnJava's checked-in Block, Decomposition_Type, Numeric_Value,
and Unikemet binary-property Java data from a clean repository checkout.

The seven payload files are populated byte-for-byte by the explicit import rows
in `dev/import-perl5/config.yaml` from the Perl source tree at commit
`de80c8ecd40c6d5b677847699e5482b44bc748c6` (`Perl/perl5`). Refresh the complete
group with `perl dev/import-perl5/sync.pl --only dev/unicode/17.0.0`. The
generator scripts prefer a complete local `perl5/lib/unicore` tree and use this
snapshot only when that optional developer checkout is unavailable.

| File | SHA-256 |
| --- | --- |
| `version` | `8c30575264b2772c7a69c5bb6069a28f0e0a7a0df735871bde2d99ee674316ac` |
| `Blocks.txt` | `c0edefaf1a19771e830a82735472716af6bf3c3975f6c2a23ffbe2580fbbcb15` |
| `PropertyAliases.txt` | `4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb` |
| `PropValueAliases.txt` | `670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01` |
| `Unikemet.txt` | `76a3081265e6eb673873f9c93d6f36062e82c7ed027c5c1a592accfbe48c20a5` |
| `extracted/DDecompositionType.txt` | `f44e5ceaf40edc1fe06ea0404e8bebc7d356dcc38aac076543b6874008a06e3e` |
| `extracted/DNumValues.txt` | `139b976bdc288be01c80f018523da769cf2845109b5a7f0f8a432db64bfedcfa` |

These files are development-only generator inputs, not runtime resources or
generated output. Regenerate the Java classes by running the corresponding
`dev/tools/generate_perl_unicode_*_data.pl` script from the repository root.
Each script verifies the Unicode version, exact hashes, and embedded notices
before producing output.

Copyright, trademark, authorship, source, and terms notices remain embedded in
the copied files exactly as supplied. See each source file for its applicable
Unicode terms and attribution.
