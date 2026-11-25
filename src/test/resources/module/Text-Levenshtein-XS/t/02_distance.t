#!perl -T
#
# This file is part of Text-Levenshtein-XS
#
# This software is copyright (c) 2016 by Nick Logan.
#
# This is free software; you can redistribute it and/or modify it under
# the same terms as the Perl 5 programming language system itself.
#
use 5.008;
use strict;
use warnings FATAL => 'all';
use Test::More;
use Text::Levenshtein::XS qw/distance/;

subtest 'distance with no max distance' => sub { 
    is( distance('four','for'),             1,      'test distance insertion');
    is( distance('four','four'),            0, '     test distance matching');
    is( distance('four','fourth'),          2,      'test distance deletion');
    is( distance('four','fuor'),            2,      'test distance (no) transposition');
    is( distance('four','fxxr'),            2,      'test distance substitution');
    is( distance('four','FOuR'),            3,      'test distance case');
    is( distance('four',''),                4,      'test distance target empty');
    is( distance('','four'),                4,      'test distance source empty');
    is( distance('',''),                    0,      'test distance source and target empty');
    is( distance('111','11'),               1,      'test distance numbers');
    is( distance('xxx' x 10000,'xa' x 500), 29500,  'test larger source and target');
    is( distance('abcdxx','xx'),            4,      'test distance');
    is( distance('xx','abcdxx'),            4,      'test distance');
};

subtest 'distance using a max distance' => sub {
    is( distance('xxx','hhh',  3),          3,      'test distance == maxdistance');
    is( distance('xxx','hhhh', 3),          undef,  'test distance > maxdistance');
    is( distance('xxx','hhhh', 5),          4,      'test distance < maxdistance');
    is( distance('xxx','hhhh', 0),          4,      'test maxdistance == 0');
    is( distance('xxx','hhhh', undef),      4,      'test maxdistance == undef');
    is( distance('xxx','hxhh', 2),          undef,  'test distance > maxdistance');
    is( distance('abcd','efgh', 1),         undef,  'test distance > maxdistance');
    is( distance('abcd','efgh', 0),         4,      'test maxdistance == 0');
    is( distance('a' x 101,'a', 200),       100,    'test large distance > large maxdistance');
    is( distance('abcd' x 100,'a', 1),      undef,  'test large distance > maxdistance');
    is( distance('abcd','', 1),             undef,  'test distance > maxdistance with blank target');
    is( distance('abcd','', 4),             4,      'test maxdistance == length(source) with blank target');
    is( distance('','abcd', 1),             undef,  'test distance > maxdistance with blank source');
    is( distance('','abcd', 4),             4,      'test maxdistance == length(target) with blank source');
    is( distance('abcdxx','xx', 1),         undef,  'test distance > maxdistance with length difference > max distance; longer source');
    is( distance('abcdxx','xx', 4),         4,      'test maxdistance == length difference between source and target; longer source');
    is( distance('xx','abcdxx', 1),         undef,  'test distance > maxdistance with length difference > max distance; longer target');
    is( distance('xx','abcdxx', 4),         4,      'test maxdistance == length difference between source and target; longer target');
    is( distance('x','123456789x', 8),      undef,  'test maxdistance == length difference between source and target; clear example.');
    is( distance('x','123456789x', 9),      9,      'test maxdistance == length difference between source and target; clear example.');
};

subtest 'distance using utf8' => sub {
    use utf8;
    binmode STDOUT, ":encoding(utf8)";
    is( distance('ⓕⓞⓤⓡ','ⓕⓞⓤⓡ'),            0,      'test distance matching');
    is( distance('ⓕⓞⓤⓡ','ⓕⓞⓡ'),             1,      'test distance insertion');
    is( distance('ⓕⓞⓤⓡ','ⓕⓞⓤⓡⓣⓗ'),          2,      'test distance deletion');
    is( distance('ⓕⓞⓤⓡ','ⓕⓤⓞⓡ'),            2,      'test distance (no) transposition');
    is( distance('ⓕⓞⓤⓡ','ⓕⓧⓧⓡ'),            2,      'test distance substitution');
    is( distance('ⓕⓞⓤⓡ','ⓕⓤⓞⓡ',1),          undef,  'test distance > max distance');
    is( distance('ⓕⓞⓤⓡ','ⓕⓤⓞⓡ',2),          2,      'test distance == max distance');
    is( distance('ⓕⓞⓤⓡ','ⓕⓤⓞⓡ',100),        2,      'test distance < max distance');
    is( distance('ⓕⓞⓤⓡ','ⓕⓤⓞⓡ',0),          2,      'test distance; max distance == 0');
};

subtest 'Text::LevenshteinXS compatability' => sub {
    is( distance("foo","four"),             2,      "Correct distance foo four");
    is( distance("foo","foo"),              0,      "Correct distance foo foo");
    is( distance("cow","cat"),              2,      "Correct distance cow cat");
    is( distance("cat","moocow"),           5,      "Correct distance cat moocow");
    is( distance("cat","cowmoo"),           5,      "Correct distance cat cowmoo");
    is( distance("sebastian","sebastien"),  1,      "Correct distance sebastian sebastien");
    is( distance("more","cowbell"),         5,      "Correct distance more cowbell");
};

subtest 'Testing previous bugs/issues' => sub {
    is( distance('cuba','thing'), distance('cuba','thing',10), 'nglenn@cpan.org https://github.com/ugexe/Text--Levenshtein--XS/issues/7');

    is( distance('ACGTAG', 'AGTAG', 2), 1,     'iimog https://github.com/ugexe/Text--Levenshtein--XS/issues/10' );
    is( distance('ACGTAG', 'TCGG',  2), undef, 'iimog https://github.com/ugexe/Text--Levenshtein--XS/issues/10' );
    is( distance('ACGTAG', 'AGTAG', 1), 1,     'iimog https://github.com/ugexe/Text--Levenshtein--XS/issues/11' );
};

# Not quite supported yet
#my @foo = distance("foo","four","foo","bar");
#my @bar = (2,0,3);
#is_deeply(\@foo,\@bar,"Array test: Correct distances foo four foo bar");



done_testing();
1;



__END__