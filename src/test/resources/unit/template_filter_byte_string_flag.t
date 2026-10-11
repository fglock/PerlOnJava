use strict;
use warnings;
use Test::More;
use Encode qw(encode_utf8 is_utf8);

BEGIN {
    eval { require Template; 1 }
        or plan skip_all => 'Template Toolkit is not installed';
}

plan tests => 3;

my @flags;
my $tt = Template->new({
    FILTERS => {
        flag_probe => sub {
            push @flags, is_utf8($_[0]) ? 1 : 0;
            return $_[0];
        },
    },
}) or die Template->error;

my $bytes = encode_utf8("ø");
my $output = '';
$tt->process(\('[% FILTER flag_probe %]' . $bytes . '[% END %]'), {}, \$output)
    or die $tt->error;
is($flags[0], 0, 'Template filter receives byte input without the UTF-8 flag');
is($output, $bytes, 'Template preserves the byte input');

my $unicode = "ø";
utf8::upgrade($unicode);
@flags = ();
$output = '';
$tt->process(\('[% FILTER flag_probe %]' . $unicode . '[% END %]'), {}, \$output)
    or die $tt->error;
is($flags[0], 1, 'Template filter keeps decoded Unicode flagged');
