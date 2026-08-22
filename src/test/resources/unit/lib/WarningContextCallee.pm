package WarningContextCallee;

use strict;

{
    no warnings;
    sub disabled {
        my $format = q!%{!;
        return sprintf $format, 1;
    }
}

{
    use warnings;
    sub enabled {
        my $format = q!%{!;
        return sprintf $format, 1;
    }
}

{
    use warnings FATAL => 'printf';
    sub fatal {
        my $format = q!%{!;
        return sprintf $format, 1;
    }
}

1;
