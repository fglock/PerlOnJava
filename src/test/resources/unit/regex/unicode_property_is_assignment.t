use strict;
use warnings;
use Test::More tests => 12;

ok("\x{1E900}" =~ /\p{Is_Block=Adlam}/, 'Is_Block property prefix');
ok("\x{1E900}" =~ /\p{Is_Blk=Adlam}/, 'Is_Blk property prefix');
ok("\x{1E900}" =~ /\p{Is_Script=Adlam}/, 'Is_Script property prefix');
ok("\x{1E900}" =~ /\p{Is_Sc=Adlm}/, 'Is_Sc property prefix');
ok('A' =~ /\p{Is_General_Category=Uppercase_Letter}/,
   'Is_General_Category property prefix');
ok('A' =~ /\p{Is_Gc=Lu}/, 'Is_Gc property prefix');
ok('A' =~ /\p{Is_Age=1.1}/, 'Is_Age exact property prefix');
ok('A' =~ /\p{Is_In=1.1}/, 'Is_In cumulative property prefix');
ok('A' =~ /\p{Is_Present_In=1.1}/, 'Is_Present_In cumulative prefix');
ok('1' =~ /\p{Is_Numeric_Value=1}/, 'Is_Numeric_Value prefix');
ok("\x{0301}" =~ /\p{Is_Canonical_Combining_Class:230}/,
   'Is_ property with colon delimiter');
ok("\r" =~ /\p{Is_Line_Break=CR}/, 'Is_Line_Break prefix');
