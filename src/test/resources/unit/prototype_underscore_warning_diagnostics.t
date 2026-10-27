use strict;
use warnings;
use Test::More;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{
        use warnings 'illegalproto';
        sub warning_proto_after_underscore_scalar ($_$) { 1 }
        sub warning_proto_underscore_word (_b) { 1 }
        sub warning_proto_underscore_class ([_]) { 1 }
        1;
    };
    ok(!$@, 'malformed underscore prototypes do not prevent declarations');
}

my $warnings = join '', @warnings;
like($warnings,
    qr/Illegal character after '_' in prototype for main::warning_proto_after_underscore_scalar : \$_\$/,
    'underscore warning retains the complete prototype');
like($warnings,
    qr/Illegal character in prototype for main::warning_proto_underscore_word : _b/,
    'illegal prototype character warning is retained');
like($warnings,
    qr/Illegal character after '_' in prototype for main::warning_proto_underscore_word : _b/,
    'malformed underscore gets its additional warning');
like($warnings,
    qr/Illegal character after '_' in prototype for main::warning_proto_underscore_class : \[_\]/,
    'underscore warning retains character class delimiters');

done_testing;
