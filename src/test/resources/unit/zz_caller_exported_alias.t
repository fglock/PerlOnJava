use strict;
use warnings;
use Test::More tests => 2;

{
    package CallerAliasProvider;
    use Exporter 'import';
    our @EXPORT = qw(exported_caller);

    sub exported_caller {
        return scalar caller;
    }
}

{
    package CallerAliasClient;
    CallerAliasProvider->import;

    ::is(exported_caller(), __PACKAGE__,
        'caller uses the package of a direct exported-alias call site');
    ::is(eval { exported_caller() }, __PACKAGE__,
        'caller uses the package of an exported-alias call site inside eval');
}
