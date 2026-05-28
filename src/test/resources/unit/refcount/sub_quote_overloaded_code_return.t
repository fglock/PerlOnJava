use strict;
use warnings;

use Test::More;
use B::Deparse;
use Sub::Defer qw(defer_sub);
use Sub::Quote qw(quote_sub);

defer_sub 'QuotedConstructor::new' => sub {
    my $constructor = quote_sub(
        'QuotedConstructor::new' => 'my $marker = "quoted constructor survives"; 42',
        {},
        {
            package => 'QuotedConstructor',
            no_defer => 1,
            no_install => 1,
        },
    );
    $constructor;
};

is(QuotedConstructor->new, 42, 'deferred quoted constructor remains callable');

my $source = B::Deparse->new->coderef2text(QuotedConstructor->can('new'));
like($source, qr/quoted constructor survives/, 'deferred quoted constructor metadata survives temporary cleanup');

done_testing;
