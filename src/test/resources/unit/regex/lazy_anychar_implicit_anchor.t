use strict;
use warnings;
use Test::More;

my $same_line = 'abxx';
ok($same_line =~ /.*?x/, 'lazy leading dot-star finds the first suffix');
is($&, 'abx', 'lazy matching still keeps its shortest extent');
is($-[0], 0, 'lazy leading dot-star begins at the line start');

my $after_newline = "before\nx";
ok($after_newline =~ /.*?x/, 'lazy dot-star searches following lines');
is($&, 'x', 'non-dotall lazy dot-star does not cross a newline');
is($-[0], 7, 'implicit anchoring advances to the next line start');

my $alternative = 'ba';
ok($alternative =~ /a|.*?x/, 'an alternative before dot-star remains searchable');
is($&, 'a', 'an alternative is not incorrectly anchored to the buffer start');
is($-[0], 1, 'the alternative retains its later start offset');

done_testing;
