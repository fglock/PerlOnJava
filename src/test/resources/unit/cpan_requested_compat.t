use strict;
use warnings;
use Test::More;

use I18N::LangTags::List;
use version;

is(
    I18N::LangTags::List::name('en'),
    'English',
    'core I18N language-name table is available',
);

{
    local $SIG{__WARN__} = sub { };
    my $overflow = version->new('v9223372036854775807');
    is("$overflow", 'v.Inf', 'overflowing dotted version component stringifies as v.Inf');
}

done_testing;
